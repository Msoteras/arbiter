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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /** Only stubbed where a transition leaves a term-pausing status (which resets the deadline). */
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

    @Test
    void transitionIfStillIn_movesTheCaseAndRecordsIt_whenItWinsTheCompareAndSet() {
        Case stale = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        Case fresh = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseStateCatalog.resolve(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.claimStatusTransition(eq(1L), any(), any())).thenReturn(1);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(fresh));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Case> moved = caseStatusService.transitionIfStillIn(stale,
                CaseStatus.PENDING_CLASSIFICATION, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.SYSTEM, "clasificación: LLM_RECOMIENDA_APROBAR");

        // The re-read entity, not the copy passed in: the caller caches its own data on it before
        // saving.
        assertThat(moved).containsSame(fresh);
        CaseStatusHistory row = captureHistory();
        assertThat(row.getFromStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(row.getToStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(row.getActor()).isEqualTo(StatusChangeActor.SYSTEM);
    }

    /**
     * The sweep that arrives second. What matters isn't the empty Optional but what does NOT
     * happen: no history row and no notification.
     */
    @Test
    void transitionIfStillIn_writesNothing_whenAnotherSweepGotThereFirst() {
        Case stale = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseStateCatalog.resolve(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.claimStatusTransition(eq(1L), any(), any())).thenReturn(0);

        Optional<Case> moved = caseStatusService.transitionIfStillIn(stale,
                CaseStatus.PENDING_CLASSIFICATION, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.SYSTEM, "clasificación: LLM_RECOMIENDA_APROBAR");

        assertThat(moved).isEmpty();
        verifyNoInteractions(historyRepository, notificationService);
        verify(caseRepository, never()).save(any());
    }

    /**
     * Validation must run against the explicit expected status, not the stale copy's; otherwise the
     * CAS would guard the write while the state machine looked at data with no authority.
     */
    @Test
    void transitionIfStillIn_rejectsAnInvalidTransition_withoutTouchingTheDatabase() {
        Case stale = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);

        assertThatThrownBy(() -> caseStatusService.transitionIfStillIn(stale,
                CaseStatus.APPROVED, CaseStatus.AWAITING_DOCUMENTATION,
                StatusChangeActor.SYSTEM, "no corresponde"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        verifyNoInteractions(caseRepository, historyRepository, notificationService);
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
     * Deciding without the report would ignore the evidence that was requested. The only way back
     * is the analyst's review.
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

    /** One derivation per case: PENDING_EXPERT_REPORT can't be left by deriving again. */
    @Test
    void transition_refusesASecondDerivation() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_EXPERT_REPORT);

        assertThatThrownBy(() -> caseStatusService.transition(entity, CaseStatus.PENDING_EXPERT_REPORT,
                StatusChangeActor.ANALYST, "derivado a peritaje"))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    /**
     * Lapse closure: although it leaves a term-pausing status, the request was never fulfilled, so
     * there's no term to resume and the clock must not be touched.
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
     * Reopening: all three terminal statuses go back to the analyst. Without it an analyst's mistake,
     * or documents the insured brings late, can't be fixed within the system.
     */
    @ParameterizedTest
    @EnumSource(value = CaseStatus.class, names = {"APPROVED", "REJECTED", "LAPSED"})
    void transition_reopensAClosedCaseBackToTheAnalyst(CaseStatus terminal) {
        Case entity = caseRecord(1L, terminal);
        entity.setResponseDeadline(LocalDate.of(2020, 1, 1));
        when(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW))
                .thenReturn(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clock.instant()).thenReturn(Instant.parse("2026-08-31T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "expediente reabierto: error de carga");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        // The art. 56 term starts over: reopening to fix a mistake can't hand over an already
        // overdue case.
        assertThat(entity.getResponseDeadline())
                .isEqualTo(LocalDate.of(2026, 8, 31).plusDays(CaseStatusService.RESPONSE_TERM_DAYS));
        verify(notificationService).notifyReopened(entity);
        verify(notificationService, never()).notifyStatusChange(any(), any());
    }

    /**
     * The counterpart: an ordinary classification reaches the same PENDING_ANALYST_REVIEW and is NOT
     * a reopening; only the source status tells them apart.
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

    @Test
    void transition_refusesToReopenACaseThatIsStillOpen() {
        Case entity = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);

        assertThatThrownBy(() -> caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "expediente reabierto: no corresponde"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.AWAITING_DOCUMENTATION);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void transition_aReopenedCaseCanBeResolvedAgain() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseStateCatalog.resolve(CaseStatus.APPROVED)).thenReturn(CaseStates.of(CaseStatus.APPROVED));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));

        caseStatusService.transition(entity, CaseStatus.APPROVED,
                StatusChangeActor.ANALYST, "decisión del analista: APPROVE");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.APPROVED);
        // Resolving resets nothing: the term stops because the status is terminal, which
        // verifyNoInteractions(clock) tells apart from a reset.
        verifyNoInteractions(clock);
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder().id(id).currentStatus(CaseStates.of(status)).build();
    }
}
