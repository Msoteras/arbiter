package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.RiskAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.RuleResult;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseClassificationRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.LlmAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RuleResultRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persistence + read mapping of the risk snapshot (its own risk_analysis row), focused on the
 * "sin scorear" contract: a claim with no scoring config must persist no risk_analysis row and
 * expose null risk — never a real LOW band.
 *
 * <p>Also covers how a Fast Track case is read back now that it produces no llm_analysis row at
 * all: the outcome comes off the case, not off a log entry's source column.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationResultsServiceTest {

    @Mock private LlmAnalysisRepository llmAnalysisRepository;
    @Mock private CaseClassificationRepository caseClassificationRepository;
    @Mock private RiskAnalysisRepository riskAnalysisRepository;
    @Mock private CaseOutcomeRepository caseOutcomeRepository;
    @Mock private RuleResultRepository ruleResultRepository;
    @Mock private OllamaProperties ollamaProperties;

    @InjectMocks private ClassificationResultsService service;

    private static ClassificationResponse response(RiskScore riskScore) {
        return ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(List.of("ok"))
                .confidence(1.0)
                .deterministicFastTrack(true)   // avoids needing the Ollama model/prompt fields
                .riskScore(riskScore)
                .build();
    }

    private static CaseOutcomeRepository.CaseOutcome outcome(boolean fastTrack) {
        return new CaseOutcomeRepository.CaseOutcome(fastTrack, null, "Martina Soteras");
    }

    @Test
    void scoredClaim_persistsRiskAnalysis() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH,
                List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")), 3L);

        service.saveResult(7L, response(score), null, 120);

        ArgumentCaptor<RiskAnalysis> captor = ArgumentCaptor.forClass(RiskAnalysis.class);
        verify(riskAnalysisRepository).save(captor.capture());
        RiskAnalysis saved = captor.getValue();
        assertThat(saved.getCaseId()).isEqualTo(7L);
        assertThat(saved.getRiskScore()).isEqualByComparingTo("0.720");
        assertThat(saved.getRiskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(saved.getRiskBreakdown()).isEqualTo(score.breakdown());
    }

    /** D29 · con qué configuración se calculó el score, para poder explicarlo después. */
    @Test
    void scoredClaim_recordsWhichScoringConfigurationWasUsed() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH, List.of(), 3L);

        service.saveResult(7L, response(score), null, 120);

        verify(caseOutcomeRepository).saveScoringConfiguration(7L, 3L);
    }

    /** El baseline no es una fila de {@code scoring_configuration}: no hay id que apuntar. */
    @Test
    void baselineScore_recordsNoScoringConfiguration() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH, List.of(), null);

        service.saveResult(7L, response(score), null, 120);

        verify(caseOutcomeRepository, never()).saveScoringConfiguration(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noConfigClaim_doesNotPersistRiskAnalysis() {
        service.saveResult(7L, response(RiskScore.notScored()), null, 120);

        verify(riskAnalysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fastTrack_isRecordedOnTheCaseAndNotAsAnLlmAnalysis() {
        service.saveResult(7L, response(RiskScore.notScored()), null, 120);

        // The model never ran, and the table's CHECK rejects FAST_TRACK as a recommendation —
        // so the outcome lives on the case (decision #6).
        verify(caseOutcomeRepository).markFastTracked(7L);
        verify(llmAnalysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ruleFindings_areAuditedIntoRuleResult() {
        ClassificationResponse response = ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .factors(List.of("La cobertura no cubre el hecho generador declarado"))
                .confidence(1.0)
                .deterministicFastTrack(false)
                .ruleFindings(List.of(new RuleFinding(3L, "COVERAGE_EXCLUSION", false, "claimCause=Hurto (id=3)")))
                .build();

        service.saveResult(7L, response, null, 50);

        ArgumentCaptor<RuleResult> captor = ArgumentCaptor.forClass(RuleResult.class);
        verify(ruleResultRepository).save(captor.capture());
        RuleResult saved = captor.getValue();
        assertThat(saved.getCaseId()).isEqualTo(7L);
        assertThat(saved.getRuleId()).isEqualTo(3L);
        assertThat(saved.getRuleType()).isEqualTo("COVERAGE_EXCLUSION");
        assertThat(saved.getResult()).isEqualTo("FAIL");
        assertThat(saved.getEvaluatedValue()).contains("id=3");
        assertThat(saved.getEvaluatedAt()).isNotNull();
    }

    @Test
    void isolatedFlow_noCaseId_writesNoRuleResult() {
        ClassificationResponse response = ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(List.of("ok"))
                .confidence(1.0)
                .deterministicFastTrack(true)
                .ruleFindings(List.of(new RuleFinding(3L, "COVERAGE_EXCLUSION", true, "claimCause=Robo (id=2)")))
                .build();

        service.saveResult(null, response, null, 50);

        verify(ruleResultRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getStatus_noRisk_exposedAsSinScorearNotLow() {
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_RECOMIENDA_APROBAR)));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(false));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isNull();
        assertThat(exposed.riskBand()).isNull();
        assertThat(exposed.riskBreakdown()).isNull();
    }

    @Test
    void getStatus_scored_exposesBandAndScore() {
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_NO_RECOMIENDA_APROBAR)));
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(false));

        RiskAnalysis analysis = new RiskAnalysis();
        analysis.setCaseId(7L);
        analysis.setRiskScore(new BigDecimal("0.720"));
        analysis.setRiskBand(RiskBand.HIGH);
        analysis.setRiskBreakdown(List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(analysis));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isCloseTo(0.72, within(1e-9));
        assertThat(exposed.riskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(exposed.riskBreakdown()).hasSize(1);
    }

    @Test
    void getStatus_exposesTheReasonsAsFactors() {
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_NO_RECOMIENDA_APROBAR)));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(false));

        ClaimResponse exposed = service.getStatus(7L);

        // They are llm_reason rows now, not a serialized list — the API shape is unchanged.
        assertThat(exposed.factors()).containsExactly("factor-1", "factor-2");
        assertThat(exposed.insuredName()).isEqualTo("Martina Soteras");
        assertThat(exposed.deterministicFastTrack()).isFalse();
    }

    @Test
    void getStatus_fastTracked_reportsItWithoutAnAnalysisRow() {
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(true));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.deterministicFastTrack()).isTrue();
        assertThat(exposed.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(exposed.confidence()).isEqualTo(1.0);
    }

    @Test
    void getStatus_notClassifiedYet_reportsNothing() {
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(false));

        ClaimResponse exposed = service.getStatus(7L);

        // The case that used to be indistinguishable from a Fast Track before the outcome moved
        // onto the case row.
        assertThat(exposed.classification()).isNull();
        assertThat(exposed.deterministicFastTrack()).isFalse();
    }

    private LlmAnalysis analysis(Classification recommendation) {
        LlmAnalysis analysis = new LlmAnalysis();
        analysis.setCaseId(7L);
        analysis.setRecommendation(recommendation);
        analysis.setModel("qwen3-vl");
        analysis.setPromptVersion("classification-v1");
        analysis.setAnalyzedAt(Instant.now());
        analysis.addReason("factor-1");
        analysis.addReason("factor-2");
        return analysis;
    }
}
