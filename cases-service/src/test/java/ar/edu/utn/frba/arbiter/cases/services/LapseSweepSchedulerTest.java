package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LapseSweepSchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    /** 18 meses antes de TODAY, medianoche UTC — lo que el scheduler le pasa al repositorio. */
    private static final Instant EXPECTED_THRESHOLD =
            LocalDateTime.of(2025, 2, 24, 0, 0).toInstant(ZoneOffset.UTC);

    @Mock
    private CaseRepository caseRepository;
    @Mock
    private InsurerRepository insurerRepository;
    @Mock
    private CaseStatusService caseStatusService;

    private LapseSweepScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        scheduler = new LapseSweepScheduler(caseRepository, insurerRepository, caseStatusService, clock);
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
    }

    @Test
    void queriesAwaitingDocumentationCasesStaleFor18Months() {
        when(caseRepository.findStaleByStatus(any(), any())).thenReturn(List.of());

        scheduler.sweepLapsedCases();

        verify(caseRepository).findStaleByStatus(
                eq(CaseStatus.AWAITING_DOCUMENTATION.name()), eq(EXPECTED_THRESHOLD));
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void transitionsEveryStaleCaseToLapsed() {
        Case stale1 = staleCase(1L);
        Case stale2 = staleCase(2L);
        when(caseRepository.findStaleByStatus(any(), any())).thenReturn(List.of(stale1, stale2));

        scheduler.sweepLapsedCases();

        verify(caseStatusService).transition(eq(stale1), eq(CaseStatus.LAPSED), eq(StatusChangeActor.SYSTEM), any());
        verify(caseStatusService).transition(eq(stale2), eq(CaseStatus.LAPSED), eq(StatusChangeActor.SYSTEM), any());
    }

    /** El motivo queda en el historial: no puede ser un texto vacío ni genérico. */
    @Test
    void theReasonNamesTheRuleAndTheWindow() {
        Case stale = staleCase(1L);
        when(caseRepository.findStaleByStatus(any(), any())).thenReturn(List.of(stale));

        scheduler.sweepLapsedCases();

        verify(caseStatusService).transition(eq(stale), eq(CaseStatus.LAPSED), eq(StatusChangeActor.SYSTEM),
                eq("Caducidad por 18 meses de inacción del asegurado desde la denuncia (regla interna)"));
    }

    @Test
    void oneInsurerFailing_doesNotStopTheRest() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        Case stale = staleCase(3L);
        when(caseRepository.findStaleByStatus(any(), any()))
                .thenThrow(new RuntimeException("schema down"))
                .thenReturn(List.of(stale));

        scheduler.sweepLapsedCases();

        verify(caseStatusService).transition(eq(stale), eq(CaseStatus.LAPSED), eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void noInsurers_doesNothing() {
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of());

        scheduler.sweepLapsedCases();

        verify(caseRepository, never()).findStaleByStatus(any(), any());
        verifyNoInteractions(caseStatusService);
    }

    private Case staleCase(Long id) {
        return Case.builder().id(id).currentStatus(CaseStates.of(CaseStatus.AWAITING_DOCUMENTATION)).build();
    }

    private Insurer insurer(Long id, String schemaName) {
        return Insurer.builder()
                .id(id)
                .legalName("Seguros " + id + " S.A.")
                .name("Seguros " + id)
                .taxId("30-0000000" + id + "-0")
                .active(true)
                .schemaName(schemaName)
                .build();
    }
}
