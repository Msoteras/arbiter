package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.LlmProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseClassificationRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.LlmAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The analyst's decision points at the analysis it was based on. */
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
    private LlmProperties llmProperties;

    @InjectMocks
    private ClassificationResultsService resultsService;

    /**
     * Returns a different instance with an id, not the argument: tests assert the argument had no id.
     */
    @BeforeEach
    void savedDecisionComesBackWithAnId() {
        lenient().when(caseClassificationRepository.save(any(CaseClassification.class)))
                .thenAnswer(invocation -> {
                    CaseClassification persisted = new CaseClassification();
                    persisted.setId(PERSISTED_DECISION_ID);
                    return persisted;
                });
    }

    private static final Long PERSISTED_DECISION_ID = 7L;

    @Test
    void recordAnalystDecision_returnsTheIdOfThePersistedDecision() {
        // cases-service stores this id on cases.classification_id: it's the audit link.
        Long caseId = 42L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        Long classificationId = resultsService.recordAnalystDecision(
                caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

        assertThat(classificationId).isEqualTo(PERSISTED_DECISION_ID);
    }

    @Test
    void recordAnalystDecision_freezesTheAttemptCounterOntoTheAuditRow() {
        Long caseId = 42L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, 4));

        assertThat(captureDecision().getClassificationAttempts()).isEqualTo(4);
    }

    @Test
    void recordAnalystDecision_withoutAnAttemptCount_defaultsToZero() {
        // The column is NOT NULL but the request field is optional.
        Long caseId = 42L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

        assertThat(captureDecision().getClassificationAttempts()).isZero();
    }

    @Test
    void recordAnalystDecision_linksToTheAnalysisInsteadOfCopyingIt() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findLatestByCaseId(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId,
                new AnalystDecisionRequest(1L, "APROBAR", "Documentación completa y consistente", null));

        CaseClassification saved = captureDecision();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getLlmAnalysis()).isSameAs(analysis);
        assertThat(saved.getAnalystId()).isEqualTo(1L);
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
        // The audit record must keep the justification.
        assertThat(saved.getAnalystJustification()).isEqualTo("Documentación completa y consistente");
        assertThat(saved.getDecidedAt()).isNotNull();
    }

    @Test
    void recordAnalystDecision_rejectNormalization() {
        Long caseId = 7L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(2L, "RECHAZAR", null, null));

        assertThat(captureDecision().getDecision()).isEqualTo("REJECT");
    }

    @Test
    void recordAnalystDecision_leavesTheAnalysisUntouched() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findLatestByCaseId(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APPROVE", null, null));

        // The audit trail is immutable: recording a verdict must not rewrite what the model said.
        verify(llmAnalysisRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(analysis.getRecommendation()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(analysis.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.85));
    }

    @Test
    void recordAnalystDecision_onAFastTrackedCase_savesWithoutAnAnalysis() {
        Long caseId = 5L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(caseId))
                .thenReturn(new CaseOutcomeRepository.CaseOutcome(true, null, "Martina Soteras"));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

        // Fast Track skips the model but not the analyst: the decision has no analysis to point at.
        CaseClassification saved = captureDecision();
        assertThat(saved.getLlmAnalysis()).isNull();
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
    }

    @Test
    void recordAnalystDecision_throwsWhenTheCaseWasNeverClassified() {
        Long caseId = 99L;
        when(llmAnalysisRepository.findLatestByCaseId(caseId)).thenReturn(Optional.empty());
        when(caseOutcomeRepository.findOutcome(caseId))
                .thenReturn(new CaseOutcomeRepository.CaseOutcome(false, null, null));

        assertThatThrownBy(() ->
                resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APPROVE", null, null)))
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
