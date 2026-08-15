package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseStatusServiceTest {

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseStatusHistoryRepository historyRepository;

    @Mock
    private CaseStateCatalog caseStateCatalog;

    /** Notifying is a side effect of the transition, not part of it: these tests cover the move. */
    @Mock
    private CaseNotificationService notificationService;

    @InjectMocks
    private CaseStatusService caseStatusService;

    @Test
    void recordCreation_appendsHistoryFromNull_withoutSavingCase() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);

        caseStatusService.recordCreation(entity, StatusChangeActor.INSURED, "denuncia registrada");

        CaseStatusHistory row = captureHistory();
        assertThat(row.getCaseId()).isEqualTo(1L);
        assertThat(row.getFromStatus()).isNull();
        assertThat(row.getToStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(row.getActor()).isEqualTo(StatusChangeActor.INSURED);
        assertThat(row.getReason()).isEqualTo("denuncia registrada");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void transition_recordsFromCurrent_setsNewStatus_andSavesCase() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseStateCatalog.resolve(CaseStatus.AWAITING_DOCUMENTATION))
                .thenReturn(CaseStates.of(CaseStatus.AWAITING_DOCUMENTATION));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.AWAITING_DOCUMENTATION,
                StatusChangeActor.SYSTEM, "clasificación: FALTA_DOCUMENTACION");

        CaseStatusHistory row = captureHistory();
        assertThat(row.getFromStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(row.getToStatus()).isEqualTo(CaseStatus.AWAITING_DOCUMENTATION);
        assertThat(row.getActor()).isEqualTo(StatusChangeActor.SYSTEM);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.AWAITING_DOCUMENTATION);
        verify(caseRepository).save(entity);
    }

    private CaseStatusHistory captureHistory() {
        ArgumentCaptor<CaseStatusHistory> captor = ArgumentCaptor.forClass(CaseStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder().id(id).currentStatus(CaseStates.of(status)).build();
    }
}
