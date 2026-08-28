package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void transition_allowsDerivingACaseUnderReviewToAnExpert() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseStateCatalog.resolve(CaseStatus.PENDING_EXPERT_REPORT))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_EXPERT_REPORT));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.PENDING_EXPERT_REPORT,
                StatusChangeActor.ANALYST, "derivado a peritaje: Estudio Verifica S.R.L.");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_EXPERT_REPORT);
    }

    @Test
    void transition_returnsADerivedCaseToTheAnalyst() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_EXPERT_REPORT);
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "informe de peritaje recibido: FRAUD_CONFIRMED");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
    }

    /**
     * Decidir sin el informe sería resolver el expediente ignorando la evidencia que se salió a
     * buscar. El único camino de vuelta es la revisión del analista.
     */
    @Test
    void transition_refusesToResolveACaseThatIsStillWithTheExpert() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_EXPERT_REPORT);

        assertThatThrownBy(() -> caseStatusService.transition(entity, CaseStatus.APPROVED,
                StatusChangeActor.ANALYST, "decisión del analista: APPROVE"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_EXPERT_REPORT);
        verify(caseRepository, never()).save(any());
    }

    /** Una derivación por expediente: desde PENDING_EXPERT_REPORT no se sale derivando de nuevo. */
    @Test
    void transition_refusesASecondDerivation() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_EXPERT_REPORT);

        assertThatThrownBy(() -> caseStatusService.transition(entity, CaseStatus.PENDING_EXPERT_REPORT,
                StatusChangeActor.ANALYST, "derivado a peritaje"))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder().id(id).currentStatus(CaseStates.of(status)).build();
    }
}
