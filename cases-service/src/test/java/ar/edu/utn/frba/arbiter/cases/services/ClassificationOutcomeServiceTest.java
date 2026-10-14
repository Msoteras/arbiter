package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished.Outcome;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassificationOutcomeServiceTest {

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ClaimsAnalysisClient claimsAnalysisClient;

    @InjectMocks
    private ClassificationOutcomeService service;

    @Test
    void completed_readsTheResultLikeThePollingDoes() {
        Case entity = pendingCase(7L);
        when(caseRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(true);

        service.onClassificationFinished(7L, Outcome.COMPLETED);

        verify(claimsAnalysisClient).refreshClassification(entity);
        verify(caseStatusService, never()).transitionIfStillIn(any(), any(), any(), any(), any());
    }

    @Test
    void completed_withoutAFreshResult_leavesTheCaseToThePolling() {
        Case entity = pendingCase(7L);
        when(caseRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        assertThatCode(() -> service.onClassificationFinished(7L, Outcome.COMPLETED)).doesNotThrowAnyException();

        verify(caseStatusService, never()).transitionIfStillIn(any(), any(), any(), any(), any());
    }

    @Test
    void failed_marksTheCaseFailedRightAway_withTheReasonClassificationRecorded() {
        Case entity = pendingCase(7L);
        entity.setClassificationFailureReason(ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(caseStatusService.transitionIfStillIn(entity, CaseStatus.PENDING_CLASSIFICATION,
                CaseStatus.CLASSIFICATION_FAILED, StatusChangeActor.SYSTEM,
                "clasificación fallida (infrastructure)")).thenReturn(Optional.of(entity));

        service.onClassificationFinished(7L, Outcome.FAILED);

        verify(caseStatusService).transitionIfStillIn(entity, CaseStatus.PENDING_CLASSIFICATION,
                CaseStatus.CLASSIFICATION_FAILED, StatusChangeActor.SYSTEM,
                "clasificación fallida (infrastructure)");
        verifyNoInteractions(claimsAnalysisClient);
    }

    @Test
    void failed_caseThatAlreadyLeftPending_isLeftAsItIs() {
        Case entity = pendingCase(7L);
        when(caseRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(caseStatusService.transitionIfStillIn(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.onClassificationFinished(7L, Outcome.FAILED)).doesNotThrowAnyException();
    }

    @Test
    void unknownCase_isNotFound() {
        when(caseRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onClassificationFinished(7L, Outcome.COMPLETED))
                .isInstanceOf(CaseNotFoundException.class);
        verifyNoInteractions(claimsAnalysisClient, caseStatusService);
    }

    @Test
    void markFailed_withoutARecordedReason_keepsTheReasonAsGiven() {
        Case entity = pendingCase(7L);
        when(caseStatusService.transitionIfStillIn(any(), any(), any(), any(), any())).thenReturn(Optional.of(entity));

        service.markFailed(entity, "clasificación fallida: sin resultado después de 3 h");

        verify(caseStatusService).transitionIfStillIn(entity, CaseStatus.PENDING_CLASSIFICATION,
                CaseStatus.CLASSIFICATION_FAILED, StatusChangeActor.SYSTEM,
                "clasificación fallida: sin resultado después de 3 h");
    }

    private Case pendingCase(Long id) {
        return Case.builder().id(id).currentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION)).build();
    }
}
