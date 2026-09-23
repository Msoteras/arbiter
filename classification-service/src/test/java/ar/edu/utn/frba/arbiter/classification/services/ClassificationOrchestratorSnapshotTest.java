package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The policy snapshot makes a classification reproducible after the insurer's data changes. */
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
    @Mock private ClaimCauseRepository claimCauseRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    /** Even the shortest path (Fast Track, no documents) records the snapshot. */
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
        when(coverageScopeEvaluator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(CoverageScopeEvaluator.Result.none());
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(true, List.of("ok"), List.of()));
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
        assertThat(snapshot.totalAmountClaimed()).isEqualByComparingTo("2440000");
        assertThat(snapshot.inForce()).isTrue();            // the event falls within the validity window
    }

    /** What the settlement needs frozen, so an amount authorized later can still be explained. */
    @Test
    void freezesWhatTheSettlementWillNeedToRecomputeTheAmount() {
        orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of());

        Snapshot snapshot = capturedSnapshot();
        // Installments still to fall due are counted up to this date.
        assertThat(snapshot.effectiveTo()).isEqualTo(RiskFixtures.POLICY_START.atStartOfDay().plusYears(1));
        assertThat(snapshot.installmentAmount()).isEqualByComparingTo("8000");
        assertThat(snapshot.overdueBalance()).isEqualByComparingTo("0");
        // No prior claims in the year: first event, paid at 100%.
        assertThat(snapshot.eventsInYear()).isEqualTo(1);
    }

    /** Same 12-month window as MAX_EVENTS_YEAR: the cap and the payable percentage must agree. */
    @Test
    void countsThisClaimAsTheSecondEventWhenOneFallsInsideTheYear() {
        when(insurerAdapter.getHistory(any())).thenReturn(InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(1)
                .totalAmountClaimed(new BigDecimal("300000"))
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of(InsuredHistory.ClaimRecord.builder()
                        .claimId("1")
                        .date(RiskFixtures.EVENT_DATE.toLocalDate().minusMonths(3))
                        .branch("Celulares")
                        .status("LIQUIDADO")
                        .build()))
                .build());

        orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of());

        assertThat(capturedSnapshot().eventsInYear()).isEqualTo(2);
    }

    @Test
    void keepsTheRawInsurerAnswer() {
        orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of());

        assertThat(capturedSnapshot().payload())
                .contains("POL-CEL-2024-001")
                .contains("previousClaimsCount");
    }

    /** An event outside the validity window is recorded as such. */
    @Test
    void recordsThePolicyAsNotInForceWhenTheEventFallsOutside() {
        orchestrator.classify(
                CASE_ID,
                RiskFixtures.claim(new BigDecimal("100000"), LocalDateTime.of(2029, 3, 1, 10, 0)),
                List.of());

        assertThat(capturedSnapshot().inForce()).isFalse();
    }

    /** Without a case there's nothing to hang the snapshot on. */
    @Test
    void isolatedClassificationRecordsNothing() {
        orchestrator.classify(RiskFixtures.claim(new BigDecimal("100000")), List.of());

        verify(policySnapshotRepository, never()).save(any(), any());
    }

    /** Best-effort: a snapshot that can't be written must not sink the classification. */
    @Test
    void aFailedSnapshotDoesNotBreakTheClassification() {
        doThrow(new RuntimeException("boom")).when(policySnapshotRepository).save(any(), any());

        assertThat(orchestrator.classify(CASE_ID, RiskFixtures.claim(new BigDecimal("100000")), List.of()))
                .isNotNull();
    }
}
