package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.LlmClient;
import ar.edu.utn.frba.arbiter.classification.config.LlmProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
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
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
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
 * An unscored claim persists no risk_analysis row and exposes null risk, never a real LOW band.
 * A rules outcome writes no llm_analysis row, so it is read from the case.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationResultsServiceTest {

    @Mock private LlmAnalysisRepository llmAnalysisRepository;
    @Mock private CaseClassificationRepository caseClassificationRepository;
    @Mock private RiskAnalysisRepository riskAnalysisRepository;
    @Mock private CaseOutcomeRepository caseOutcomeRepository;
    @Mock private RuleResultRepository ruleResultRepository;
    @Mock private LlmClient llmClient;
    @Mock private LlmProperties llmProperties;

    @InjectMocks private ClassificationResultsService service;

    private static ClassificationResponse response(RiskScore riskScore) {
        return ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(List.of("ok"))
                .confidence(1.0)
                .resolvedByRules(true)   // avoids needing the LLM model/prompt fields
                .riskScore(riskScore)
                .build();
    }

    /** @param rulesClassification null when the model decided */
    private static CaseOutcomeRepository.CaseOutcome outcome(Classification rulesClassification) {
        return new CaseOutcomeRepository.CaseOutcome(rulesClassification, null, "Martina Soteras");
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

    /** Records which configuration computed the score. */
    @Test
    void scoredClaim_recordsWhichScoringConfigurationWasUsed() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH, List.of(), 3L);

        service.saveResult(7L, response(score), null, 120);

        verify(caseOutcomeRepository).saveScoringConfiguration(7L, 3L);
    }

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

        // The model never ran and llm_analysis rejects FAST_TRACK, so the outcome lives on the case.
        verify(caseOutcomeRepository).saveRulesClassification(7L, Classification.FAST_TRACK);
        verify(llmAnalysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** Shares its literal with a model recommendation, which is why it can't go to llm_analysis. */
    @Test
    void coverageExclusion_isRecordedOnTheCaseAndNotAsAnLlmAnalysis() {
        ClassificationResponse exclusion = ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .factors(List.of("La cobertura no cubre el hecho generador declarado"))
                .confidence(1.0)
                .resolvedByRules(true)
                .build();

        service.saveResult(7L, exclusion, null, 50);

        verify(caseOutcomeRepository).saveRulesClassification(7L, Classification.LLM_SOLICITA_REVISION_MANUAL);
        verify(llmAnalysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** Otherwise a case that went from a rules outcome to the model would keep reading as the former. */
    @Test
    void modelOutcome_clearsTheRulesColumnAndLogsAnLlmAnalysis() {
        ClassificationResponse model = ClassificationResponse.builder()
                .classification(Classification.LLM_RECOMIENDA_APROBAR)
                .factors(List.of("Relato consistente"))
                .confidence(0.9)
                .resolvedByRules(false)
                .build();

        service.saveResult(7L, model, null, 50);

        verify(caseOutcomeRepository).saveRulesClassification(7L, null);
        verify(llmAnalysisRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ruleFindings_areAuditedIntoRuleResult() {
        ClassificationResponse response = ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .factors(List.of("La cobertura no cubre el hecho generador declarado"))
                .confidence(1.0)
                .resolvedByRules(true)
                .ruleFindings(List.of(new RuleFinding(3L, "COVERAGE_INCLUSION", false, "claimCause=Hurto (id=3)")))
                .build();

        service.saveResult(7L, response, null, 50);

        ArgumentCaptor<RuleResult> captor = ArgumentCaptor.forClass(RuleResult.class);
        verify(ruleResultRepository).save(captor.capture());
        RuleResult saved = captor.getValue();
        assertThat(saved.getCaseId()).isEqualTo(7L);
        assertThat(saved.getRuleId()).isEqualTo(3L);
        assertThat(saved.getRuleType()).isEqualTo("COVERAGE_INCLUSION");
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
                .resolvedByRules(true)
                .ruleFindings(List.of(new RuleFinding(3L, "COVERAGE_INCLUSION", true, "claimCause=Robo (id=2)")))
                .build();

        service.saveResult(null, response, null, 50);

        verify(ruleResultRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getStatus_noRisk_exposedAsUnscoredNotLow() {
        when(llmAnalysisRepository.findLatestByCaseId(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_RECOMIENDA_APROBAR)));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(null));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isNull();
        assertThat(exposed.riskBand()).isNull();
        assertThat(exposed.riskBreakdown()).isNull();
    }

    @Test
    void getStatus_scored_exposesBandAndScore() {
        when(llmAnalysisRepository.findLatestByCaseId(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_NO_RECOMIENDA_APROBAR)));
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(null));

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
        when(llmAnalysisRepository.findLatestByCaseId(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_NO_RECOMIENDA_APROBAR)));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(null));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.factors()).containsExactly("factor-1", "factor-2");
        assertThat(exposed.insuredName()).isEqualTo("Martina Soteras");
        assertThat(exposed.resolvedByRules()).isFalse();
    }

    @Test
    void getStatus_fastTracked_reportsItWithoutAnAnalysisRow() {
        when(llmAnalysisRepository.findLatestByCaseId(7L)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(Classification.FAST_TRACK));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.resolvedByRules()).isTrue();
        assertThat(exposed.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(exposed.confidence()).isEqualTo(1.0);
    }

    /** The append-only table still holds an earlier model run: none of it may leak into this one. */
    @Test
    void getStatus_rulesOutcome_winsOverAnOlderModelRun() {
        when(llmAnalysisRepository.findLatestByCaseId(7L))
                .thenReturn(Optional.of(analysis(Classification.LLM_RECOMIENDA_APROBAR)));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L))
                .thenReturn(outcome(Classification.LLM_NO_RECOMIENDA_APROBAR));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.resolvedByRules()).isTrue();
        assertThat(exposed.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(exposed.factors()).isNull();
        assertThat(exposed.analyzedAt()).isNull();
    }

    /** The audit row must not point at a model run that didn't produce what the analyst decided on. */
    @Test
    void analystDecisionOnARulesOutcome_linksNoModelAnalysis() {
        when(caseOutcomeRepository.findOutcome(7L))
                .thenReturn(outcome(Classification.LLM_NO_RECOMIENDA_APROBAR));
        when(caseClassificationRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordAnalystDecision(7L, new AnalystDecisionRequest(1L, "REJECT", "Prescripto", 1));

        ArgumentCaptor<CaseClassification> captor = ArgumentCaptor.forClass(CaseClassification.class);
        verify(caseClassificationRepository).save(captor.capture());
        assertThat(captor.getValue().getLlmAnalysis()).isNull();
        verify(llmAnalysisRepository, never()).findLatestByCaseId(7L);
    }

    @Test
    void getStatus_notClassifiedYet_reportsNothing() {
        when(llmAnalysisRepository.findLatestByCaseId(7L)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(7L)).thenReturn(outcome(null));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.classification()).isNull();
        assertThat(exposed.resolvedByRules()).isFalse();
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

    /** "All passed" and "nothing ran" must differ. */
    @Test
    void ruleResultsIncludeThePasses() {
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L)).thenReturn(List.of(
                ruleResult(1L, "POLICY_IN_FORCE", "PASS", "eventDate=20/08/2026 coverageWindow=01/01/2026..01/01/2027"),
                ruleResult(2L, "REPORT_DEADLINE", "FAIL", "reportedAt=+99h max=72h")));

        List<RuleResultResponse> results = service.getRuleResults(7L);

        assertThat(results).extracting(RuleResultResponse::result).containsExactly("PASS", "FAIL");
        assertThat(results).extracting(RuleResultResponse::ruleType)
                .containsExactly("POLICY_IN_FORCE", "REPORT_DEADLINE");
    }

    @Test
    void ruleResultsCarryTheEvaluatedValue() {
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L)).thenReturn(List.of(
                ruleResult(1L, "REPORT_DEADLINE", "FAIL", "reportedAt=+99h max=72h")));

        assertThat(service.getRuleResults(7L))
                .singleElement()
                .extracting(RuleResultResponse::evaluatedValue)
                .isEqualTo("reportedAt=+99h max=72h");
    }

    @Test
    void aCaseWithNoRulesEvaluatedYieldsAnEmptyList() {
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L)).thenReturn(List.of());

        assertThat(service.getRuleResults(7L)).isEmpty();
    }

    /** Append-only table: only the latest evaluation of each rule is returned. */
    @Test
    void aReclassifiedCaseShowsTheLastEvaluationOfEachRule() {
        RuleResult firstRun = ruleResult(1L, "REPORT_DEADLINE", "FAIL", "reportedAt=+99h max=72h");
        RuleResult secondRun = ruleResult(2L, "REPORT_DEADLINE", "PASS", "reportedAt=+12h max=72h");
        secondRun.setRuleId(firstRun.getRuleId());
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L))
                .thenReturn(List.of(firstRun, secondRun));

        assertThat(service.getRuleResults(7L))
                .singleElement()
                .extracting(RuleResultResponse::result)
                .isEqualTo("PASS");
    }

    /** One run writes several exclusions, one per configured rule: those are not duplicates. */
    @Test
    void sameRuleTypeOnDifferentRulesTravelsWhole() {
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L)).thenReturn(List.of(
                ruleResult(1L, "COVERAGE_EXCLUSION", "PASS", "claimCause=Hurto (id=3)"),
                ruleResult(2L, "COVERAGE_EXCLUSION", "FAIL", "claimCause=Caída (id=4)")));

        assertThat(service.getRuleResults(7L)).hasSize(2);
    }

    /** The gate's criteria carry no rule id and must not collapse into one another. */
    @Test
    void fastTrackCriteriaWithoutRuleIdAreNotCollapsed() {
        RuleResult ratio = ruleResult(1L, "FT_AMOUNT_RATIO", "PASS", "ratio=21,9% max=50,0%");
        RuleResult upToDate = ruleResult(2L, "FT_POLICY_UP_TO_DATE", "PASS", "upToDate=true");
        ratio.setRuleId(null);
        upToDate.setRuleId(null);
        when(ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(7L))
                .thenReturn(List.of(ratio, upToDate));

        assertThat(service.getRuleResults(7L)).extracting(RuleResultResponse::ruleType)
                .containsExactly("FT_AMOUNT_RATIO", "FT_POLICY_UP_TO_DATE");
    }

    private static RuleResult ruleResult(Long id, String type, String result, String evaluatedValue) {
        RuleResult row = new RuleResult();
        row.setId(id);
        row.setCaseId(7L);
        row.setRuleId(id);
        row.setRuleType(type);
        row.setResult(result);
        row.setEvaluatedValue(evaluatedValue);
        row.setEvaluatedAt(Instant.now());
        return row;
    }
}
