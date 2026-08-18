package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test of the scoring wiring in the orchestrator (no Spring context): scoring runs once per
 * classification, on both routes, and never breaks the classification when it fails.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationOrchestratorScoringTest {

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
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    private final RiskScore knownScore = new RiskScore(true, 0.42, RiskBand.MEDIUM,
            List.of(new RiskBreakdownItem("amount_ratio", 0.5, 0.45, 0.225, "monto")), 1L);

    @BeforeEach
    void stubContext() {
        when(insurerAdapter.getPolicy(any())).thenReturn(RiskFixtures.policy(true, new BigDecimal("400000")));
        when(insurerAdapter.getHistory(any())).thenReturn(RiskFixtures.history(0));
        when(rulesAdapter.getRules(any(), any(), any())).thenReturn(RiskFixtures.rules(null));
        when(coverageRuleEvaluator.evaluate(any(), any()))
                .thenReturn(new CoverageRuleEvaluator.Result(false, List.of()));
        when(temporalRuleEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(TemporalRuleEvaluator.Result.empty());
        when(fraudRecordRuleEvaluator.evaluate(any(), any()))
                .thenReturn(FraudRecordRuleEvaluator.Result.empty());
        when(coverageScopeEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(new CoverageScopeEvaluator.Result(false, List.of()));
    }

    @Test
    void fastTrackRoute_scoresExactlyOnceAndAttaches() {
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(true, List.of("ok")));
        when(riskScoringService.score(any())).thenReturn(knownScore);

        ClassificationResponse response = orchestrator.classify(RiskFixtures.claim(new BigDecimal("100000")), List.of());

        verify(riskScoringService, times(1)).score(any());
        verify(classifier, never()).classify(any());
        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.riskScore()).isEqualTo(knownScore);
    }

    @Test
    void llmRoute_scoresExactlyOnceAndAttaches() {
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(false, List.of("no")));
        when(classifier.classify(any())).thenReturn(ClassificationResponse.builder()
                .classification(Classification.LLM_RECOMIENDA_APROBAR)
                .factors(List.of("ok"))
                .confidence(0.8)
                .deterministicFastTrack(false)
                .build());
        when(promptBuilder.renderRulesAndPolicy(any(), any())).thenReturn("");
        when(promptBuilder.renderHistory(any())).thenReturn("");
        when(riskScoringService.score(any())).thenReturn(knownScore);

        ClassificationResponse response = orchestrator.classify(RiskFixtures.claim(new BigDecimal("100000")), List.of());

        verify(riskScoringService, times(1)).score(any());
        assertThat(response.classification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(response.riskScore()).isEqualTo(knownScore);
    }

    @Test
    void scoringFailure_doesNotBreakClassification() {
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(true, List.of("ok")));
        when(riskScoringService.score(any())).thenThrow(new RuntimeException("scoring boom"));

        ClassificationResponse response = orchestrator.classify(RiskFixtures.claim(new BigDecimal("100000")), List.of());

        assertThat(response).isNotNull();
        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.riskScore()).isNull();
    }
}
