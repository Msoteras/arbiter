package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A claim reported more than a year after the event is time-barred (art. 58 Ley 17.418): no LLM call
 * and no other hard rule.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationOrchestratorPrescriptionTest {

    @Mock private ClaimClassifier classifier;
    @Mock private RulesAdapter rulesAdapter;
    @Mock private InsurerAdapter insurerAdapter;
    @Mock private CoverageRuleEvaluator coverageRuleEvaluator;
    @Mock private CoverageScopeEvaluator coverageScopeEvaluator;
    @Mock private TemporalRuleEvaluator temporalRuleEvaluator;
    @Mock private FraudRecordRuleEvaluator fraudRecordRuleEvaluator;
    @Mock private FastTrackValidator fastTrackValidator;
    @Spy private ClaimCauseConsistencyEvaluator claimCauseConsistencyEvaluator = new ClaimCauseConsistencyEvaluator();
    @Mock private DocumentAnalyzer documentAnalyzer;
    @Mock private PromptBuilder promptBuilder;
    @Mock private RiskScoringService riskScoringService;
    @Mock private ImageFraudAnalysisService imageFraudAnalysisService;
    @Mock private PolicySnapshotRepository policySnapshotRepository;
    @Mock private InsuredFraudRecordRepository fraudRecordRepository;
    @Mock private DocumentAnalysisRepository documentAnalysisRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    private static ClaimReport claim(LocalDateTime eventDate, LocalDateTime reportedAt) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública.")
                .eventDate(eventDate)
                .reportedAt(reportedAt)
                .eventLocation("CABA")
                .claimedAmount(new BigDecimal("100000"))
                .attachmentsOcr(List.of())
                .build();
    }

    @Test
    void eventOlderThanOneYear_recommendsRejectionWithoutTouchingTheEngineOrTheLlm() {
        LocalDateTime eventDate = LocalDateTime.of(2024, 6, 13, 19, 45);
        LocalDateTime reportedAt = LocalDateTime.of(2026, 6, 20, 10, 0); // 2 years later
        stubMinimalContext();

        ClassificationResponse response = orchestrator.classify(claim(eventDate, reportedAt), List.of());

        assertThat(response.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.confidence()).isEqualTo(1.0);
        assertThat(response.deterministicFastTrack()).isFalse();
        assertThat(response.factors()).anyMatch(f -> f.contains("prescripto"));

        // Context resolution (policy/history/rules) always runs, so it isn't verified here.
        verify(classifier, never()).classify(any());
        verifyNoInteractions(coverageRuleEvaluator, temporalRuleEvaluator, coverageScopeEvaluator,
                fraudRecordRuleEvaluator, fastTrackValidator, documentAnalyzer);
    }

    /** At the edge: one year and one day is already time-barred. */
    @Test
    void eventReportedOneYearAndADayLater_isStillPrescribed() {
        LocalDateTime eventDate = LocalDateTime.of(2025, 6, 13, 19, 45);
        LocalDateTime reportedAt = eventDate.plusYears(1).plusDays(1);
        stubMinimalContext();

        ClassificationResponse response = orchestrator.classify(claim(eventDate, reportedAt), List.of());

        assertThat(response.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
    }

    @Test
    void eventWithinOneYear_doesNotShortCircuit() {
        LocalDateTime eventDate = LocalDateTime.of(2026, 6, 13, 19, 45);
        LocalDateTime reportedAt = eventDate.plusMonths(1);
        stubFastTrackRoute();

        ClassificationResponse response = orchestrator.classify(claim(eventDate, reportedAt), List.of());

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
    }

    /** Without `reportedAt` the gate doesn't take part: it never blocks blindly. */
    @Test
    void withoutReportedAt_doesNotParticipate() {
        LocalDateTime eventDate = LocalDateTime.of(2020, 1, 1, 0, 0); // very old, but no report date
        stubFastTrackRoute();

        ClassificationResponse response = orchestrator.classify(claim(eventDate, null), List.of());

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
    }

    /** The minimum for {@code fetchContext} and {@code withRiskScore}, which run regardless of the gate. */
    private void stubMinimalContext() {
        when(insurerAdapter.getPolicy(any())).thenReturn(RiskFixtures.policy(true, new BigDecimal("400000")));
        when(insurerAdapter.getHistory(any())).thenReturn(RiskFixtures.history(0));
        when(rulesAdapter.getRules(any(), any(), any())).thenReturn(RiskFixtures.rules(null));
        when(riskScoringService.score(any())).thenReturn(RiskScore.notScored());
    }

    private void stubFastTrackRoute() {
        stubMinimalContext();
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
}
