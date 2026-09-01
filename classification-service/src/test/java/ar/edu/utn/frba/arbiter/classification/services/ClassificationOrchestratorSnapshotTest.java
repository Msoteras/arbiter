package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository.Snapshot;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D27 · the snapshot of the policy the classification ran on. Without it the classification isn't
 * reproducible: the two factors coming from the insurer's DB ({@code policy_standing},
 * {@code claim_frequency}) are read live from a system that keeps changing.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationOrchestratorSnapshotTest {

    private static final long CASE_ID = 77L;

    @Mock private ClaimClassifier classifier;
    @Mock private RulesAdapter rulesAdapter;
    @Mock private InsurerAdapter insurerAdapter;
    @Mock private CoverageRuleEvaluator coverageRuleEvaluator;
    @Mock private CoverageScopeEvaluator coverageScopeEvaluator;
    @Mock private TemporalRuleEvaluator temporalRuleEvaluator;
    @Mock private FraudRecordRuleEvaluator fraudRecordRuleEvaluator;
    @Mock private FastTrackValidator fastTrackValidator;
    @Mock private DocumentAnalyzer documentAnalyzer;
    @Mock private PromptBuilder promptBuilder;
    @Mock private RiskScoringService riskScoringService;
    @Mock private ImageFraudAnalysisService imageFraudAnalysisService;
    @Mock private PolicySnapshotRepository policySnapshotRepository;
    @Mock private InsuredFraudRecordRepository fraudRecordRepository;
    @Mock private DocumentAnalysisRepository documentAnalysisRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    /** Fast Track with no documents: the shortest path, which still goes through the snapshot. */
    @BeforeEach
    void stubContext() {
        when(insurerAdapter.getPolicy(any())).thenReturn(RiskFixtures.policy(true, new BigDecimal("400000")));
        when(insurerAdapter.getHistory(any()))
                .thenReturn(RiskFixtures.history(2, new BigDecimal("2440000")));
        when(rulesAdapter.getRules(any(), any(), any())).thenReturn(RiskFixtures.rules(null));
        when(coverageRuleEvaluator.evaluate(any(), any()))
                .thenReturn(new CoverageRuleEvaluator.Result(false, List.of()));
        when(temporalRuleEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(TemporalRuleEvaluator.Result.empty());
        when(fraudRecordRuleEvaluator.evaluate(any(), any()))
                .thenReturn(FraudRecordRuleEvaluator.Result.empty());
        when(coverageScopeEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(CoverageScopeEvaluator.Result.none());
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(true, List.of("ok")));
    }

    private Snapshot capturedSnapshot() {
        ArgumentCaptor<Snapshot> captor = ArgumentCaptor.forClass(Snapshot.class);
        verify(policySnapshotRepository).save(eq(CASE_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    void freezesWhatTheInsurerAnsweredForThisClaim() {
        orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of());

        Snapshot snapshot = capturedSnapshot();
        assertThat(snapshot.externalPolicyNumber()).isEqualTo("POL-CEL-2024-001");
        assertThat(snapshot.sumInsured()).isEqualByComparingTo("400000");
        assertThat(snapshot.paymentsUpToDate()).isTrue();   // → factor policy_standing
        assertThat(snapshot.previousClaims()).isEqualTo(2); // → factor claim_frequency
        // The amount is frozen next to the count: alone, neither says how big that history was.
        assertThat(snapshot.totalAmountClaimed()).isEqualByComparingTo("2440000");
        assertThat(snapshot.inForce()).isTrue();            // el hecho cae dentro de la vigencia
    }

    /** The raw payload is the faithful record: the columns are its already-interpreted reading. */
    @Test
    void keepsTheRawInsurerAnswer() {
        orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of());

        assertThat(capturedSnapshot().payload())
                .contains("POL-CEL-2024-001")
                .contains("previousClaimsCount");
    }

    /** Un hecho fuera de la vigencia se fotografía como tal, no como "vigente". */
    @Test
    void recordsThePolicyAsNotInForceWhenTheEventFallsOutside() {
        orchestrator.classify(
                CASE_ID,
                RiskFixtures.claim(new BigDecimal("100000"), LocalDateTime.of(2029, 3, 1, 10, 0)),
                List.of());

        assertThat(capturedSnapshot().inForce()).isFalse();
    }

    /** The isolated classification (test endpoint) has no case to hang the snapshot on. */
    @Test
    void isolatedClassificationRecordsNothing() {
        orchestrator.classify(RiskFixtures.claim(new BigDecimal("100000")), List.of());

        verify(policySnapshotRepository, never()).save(any(), any());
    }

    /**
     * Best-effort, like the scoring and the fraud cascade: an audit row that can't be written must
     * not sink a classification an analyst is waiting on.
     */
    @Test
    void aFailedSnapshotDoesNotBreakTheClassification() {
        doThrow(new RuntimeException("boom")).when(policySnapshotRepository).save(any(), any());

        assertThat(orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of()))
                .isNotNull();
    }
}
