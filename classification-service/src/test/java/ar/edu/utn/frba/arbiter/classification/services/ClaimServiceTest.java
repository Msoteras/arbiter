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
    private LlmProperties llmProperties;

    @InjectMocks
    private ClassificationResultsService resultsService;

    /**
     * {@code JpaRepository.save} returns the already-persisted entity, and {@code
     * recordAnalystDecision} uses its id so cases-service can point
     * {@code cases.classification_id} at the verdict. The mock returned null.
     *
     * <p>A different instance is returned and not the argument: the tests capture what was passed
     * to {@code save} and check it goes without an id (it's a new row), so assigning it to the
     * argument would invalidate exactly that.
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
        // It's what ties the case to the model run that backed the verdict: without this id,
        // cases.classification_id stays null and the audit link is lost.
        Long caseId = 42L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        Long classificationId = resultsService.recordAnalystDecision(
                caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

        assertThat(classificationId).isEqualTo(PERSISTED_DECISION_ID);
    }

    @Test
    void recordAnalystDecision_freezesTheAttemptCounterOntoTheAuditRow() {
        // The live counter is cases.classification_attempts; its final value has to land in the
        // auditable record, which used to always be stored as 0.
        Long caseId = 42L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, 4));

        assertThat(captureDecision().getClassificationAttempts()).isEqualTo(4);
    }

    @Test
    void recordAnalystDecision_withoutAnAttemptCount_defaultsToZero() {
        // The column is NOT NULL and the request field is optional (an old caller doesn't send it).
        Long caseId = 42L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

        assertThat(captureDecision().getClassificationAttempts()).isZero();
    }

    @Test
    void recordAnalystDecision_linksToTheAnalysisInsteadOfCopyingIt() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId,
                new AnalystDecisionRequest(1L, "APROBAR", "Documentación completa y consistente", null));

        CaseClassification saved = captureDecision();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getLlmAnalysis()).isSameAs(analysis);
        assertThat(saved.getAnalystId()).isEqualTo(1L);
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
        // SSN Disposition 2/2023's auditable record has to store the justification, not drop it —
        // a bug found because the front asked for it and never sent it.
        assertThat(saved.getAnalystJustification()).isEqualTo("Documentación completa y consistente");
        assertThat(saved.getDecidedAt()).isNotNull();
    }

    @Test
    void recordAnalystDecision_rejectNormalization() {
        Long caseId = 7L;
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId))
                .thenReturn(Optional.of(analysis(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR)));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(2L, "RECHAZAR", null, null));

        assertThat(captureDecision().getDecision()).isEqualTo("REJECT");
    }

    @Test
    void recordAnalystDecision_leavesTheAnalysisUntouched() {
        Long caseId = 42L;
        LlmAnalysis analysis = analysis(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(analysis));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APPROVE", null, null));

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

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest(1L, "APROBAR", null, null));

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
