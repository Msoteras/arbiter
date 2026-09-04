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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** Only stubbed where a transition actually leaves a PAUSING_STATUSES status (resets the deadline). */
    @Mock
    private Clock clock;

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
        entity.setResponseDeadline(LocalDate.of(2020, 1, 1)); // stale: frozen since before the derivation
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "informe de peritaje recibido: FRAUD_CONFIRMED");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        // Art. 56 term interrupted by the derivation: leaving PENDING_EXPERT_REPORT resets it to a
        // fresh 30 days from today, not the stale date frozen while the case waited on the expert.
        assertThat(entity.getResponseDeadline())
                .isEqualTo(LocalDate.of(2026, 8, 31).plusDays(CaseStatusService.RESPONSE_TERM_DAYS));
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

    /**
     * Cierre por caducidad (LapseSweepScheduler): aunque se sale de AWAITING_DOCUMENTATION (una
     * PAUSING_STATUSES), el requerimiento nunca se cumplió — no hay plazo que reanudar, y el
     * caso no debería tocar el clock para eso.
     */
    @Test
    void transition_toLapsed_doesNotResumeTheDeadline() {
        Case entity = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);
        LocalDate staleDeadline = LocalDate.of(2020, 1, 1);
        entity.setResponseDeadline(staleDeadline);
        when(caseStateCatalog.resolve(CaseStatus.LAPSED)).thenReturn(CaseStates.of(CaseStatus.LAPSED));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.LAPSED, StatusChangeActor.SYSTEM,
                "Caducidad por 18 meses de inacción del asegurado desde la denuncia (regla interna)");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.LAPSED);
        assertThat(entity.getResponseDeadline()).isEqualTo(staleDeadline);
        verifyNoInteractions(clock);
    }

    /**
     * Reapertura ("rehabilitación"): los tres terminales vuelven al escritorio del analista. Sin
     * esto un error del analista —o la documentación que el asegurado trae tarde— no tiene arreglo
     * dentro del sistema.
     */
    @ParameterizedTest
    @EnumSource(value = CaseStatus.class, names = {"APPROVED", "REJECTED", "LAPSED"})
    void transition_reopensAClosedCaseBackToTheAnalyst(CaseStatus terminal) {
        Case entity = caseRecord(1L, terminal);
        entity.setResponseDeadline(LocalDate.of(2020, 1, 1)); // vencido hace años
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clock.instant()).thenReturn(Instant.parse("2026-08-31T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "expediente reabierto: error de carga");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        // El plazo del art. 56 arranca de cero: reabrir para corregir un error no puede entregar
        // un expediente ya vencido.
        assertThat(entity.getResponseDeadline())
                .isEqualTo(LocalDate.of(2026, 8, 31).plusDays(CaseStatusService.RESPONSE_TERM_DAYS));
        // Al asegurado se le avisa: su siniestro estaba cerrado y volvió a estar abierto.
        verify(notificationService).notifyReopened(entity);
        verify(notificationService, never()).notifyStatusChange(any(), any());
    }

    /**
     * La contracara: una clasificación normal llega al mismo PENDING_ANALYST_REVIEW y NO es una
     * reapertura. Sin el chequeo del estado de origen, el asegurado recibía "reabrimos tu
     * siniestro" cada vez que el modelo terminaba de clasificar.
     */
    @Test
    void transition_doesNotAnnounceAReopeningOnAnOrdinaryClassification() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.SYSTEM, "clasificación: LLM_RECOMIENDA_APROBAR");

        verify(notificationService, never()).notifyReopened(any());
    }

    /** No hay nada que reabrir en un expediente que sigue abierto: la máquina de estados lo corta. */
    @Test
    void transition_refusesToReopenACaseThatIsStillOpen() {
        Case entity = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);

        assertThatThrownBy(() -> caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "expediente reabierto: no corresponde"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.AWAITING_DOCUMENTATION);
        verify(caseRepository, never()).save(any());
    }

    /**
     * Un expediente reabierto vuelve al circuito completo, no a un limbo: desde la revisión se
     * puede resolver de nuevo, pedir documentación o derivar, igual que cualquier otro.
     */
    @Test
    void transition_aReopenedCaseCanBeResolvedAgain() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseStateCatalog.resolve(CaseStatus.APPROVED)).thenReturn(CaseStates.of(CaseStatus.APPROVED));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.APPROVED,
                StatusChangeActor.ANALYST, "decisión del analista: APPROVE");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.APPROVED);
        // Resolver no reinicia nada: el plazo se apaga porque el estado es terminal, no porque se
        // haya reseteado (verifyNoInteractions(clock) es lo que lo distingue).
        verifyNoInteractions(clock);
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder().id(id).currentStatus(CaseStates.of(status)).build();
    }
}
