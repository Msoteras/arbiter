package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseClassificationRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.LlmAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The analyst's decision, against the normalized model. The old shape copied the whole
 * classification snapshot onto a second log row so a later read would still find it; now the
 * decision points at the analysis, and these tests assert that link instead of the copy.
 */
@ExtendWith(MockitoExtension.class)
class AnalystDecisionTest {

    @Mock
    private LlmAnalysisRepository llmAnalysisRepository;

    @Mock
    private CaseClassificationRepository caseClassificationRepository;

    @Mock
    private RiskAnalysisRepository riskAnalysisRepository;

    @Mock
    private CaseOutcomeRepository caseOutcomeRepository;

    @Mock
    private OllamaProperties ollamaProperties;

    @InjectMocks
    private ClassificationResultsService resultsService;

    @Test
    void recordAnalystDecision_linksToTheAnalysisInsteadOfCopyingIt() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null));

        CaseClassification saved = captureDecision();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getLlmAnalysis()).isSameAs(analysis);
        assertThat(saved.getAnalystId()).isEqualTo(1L);
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
        assertThat(saved.getDecidedAt()).isNotNull();
    }

    @Test
    void recordAnalystDecision_rejectNormalization() {
        Long caseId = 7L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(2L, "RECHAZAR", null));

        assertThat(captureDecision().getDecision()).isEqualTo("REJECT");
    }

    @Test
    void recordAnalystDecision_leavesTheAnalysisUntouched() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APPROVE", null));

        // The audit trail is immutable: recording a verdict must not rewrite what the model said.
        verify(llmAnalysisRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(analysis.getRecommendation()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(analysis.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.85));
    }

    @Test
    void recordAnalystDecision_onAFastTrackedCase_savesWithoutAnAnalysis() {
        Long caseId = 5L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(caseId))
                .thenReturn(new CaseOutcomeRepository.CaseOutcome(true, null, "Martina Soteras"));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null));

        // Fast Track skips the model but not the analyst (decision #5) — so the decision exists
        // with nothing to point at.
        CaseClassification saved = captureDecision();
        assertThat(saved.getLlmAnalysis()).isNull();
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
    }

    @Test
    void recordAnalystDecision_throwsWhenTheCaseWasNeverClassified() {
        Long caseId = 99L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(caseId))
                .thenReturn(new CaseOutcomeRepository.CaseOutcome(false, null, null));

        assertThatThrownBy(() ->
                resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APPROVE", null)))
                .isInstanceOf(InvalidClassificationException.class);
    }

    private CaseClassification captureDecision() {
        ArgumentCaptor<CaseClassification> captor = ArgumentCaptor.forClass(CaseClassification.class);
        verify(caseClassificationRepository).save(captor.capture());
        return captor.getValue();
    }

    private LlmAnalysis analysis(Long caseId, Classification recommendation) {
        LlmAnalysis analysis = new LlmAnalysis();
        analysis.setId(100L);
        analysis.setCaseId(caseId);
        analysis.setRecommendation(recommendation);
        analysis.setModel("qwen3-vl");
        analysis.setPromptVersion("classification-v1");
        analysis.setConfidence(BigDecimal.valueOf(0.85));
        analysis.setAnalyzedAt(Instant.now());
        analysis.addReason("factor-1");
        analysis.addReason("factor-2");
        return analysis;
    }
}
