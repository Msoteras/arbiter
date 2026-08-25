package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
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
class DeadlineSweepSchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private CaseRepository caseRepository;
    @Mock
    private InsurerRepository insurerRepository;
    @Mock
    private AnalystNotificationService analystNotificationService;

    private DeadlineSweepScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        scheduler = new DeadlineSweepScheduler(
                caseRepository, insurerRepository, analystNotificationService, clock);
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
    }

    @Test
    void queriesTwoDaysOut_excludingTerminalStates() {
        when(caseRepository.findUnansweredDueBy(any(), any())).thenReturn(List.of());

        scheduler.sweepDeadlines();

        verify(caseRepository).findUnansweredDueBy(
                eq(TODAY.plusDays(2)), eq(List.of("APPROVED", "REJECTED")));
        verifyNoInteractions(analystNotificationService);
    }

    @Test
    void notifiesEachDueCaseWithItsComputedPriority() {
        Case critical = caseDue(1L, TODAY.plusDays(1));   // CRITICAL
        Case overdue = caseDue(2L, TODAY.minusDays(2));   // OVERDUE
        when(caseRepository.findUnansweredDueBy(any(), any()))
                .thenReturn(List.of(critical, overdue));

        scheduler.sweepDeadlines();

        verify(analystNotificationService).notifyDeadline(critical, DeadlinePriority.CRITICAL, TODAY);
        verify(analystNotificationService).notifyDeadline(overdue, DeadlinePriority.OVERDUE, TODAY);
    }

    @Test
    void oneInsurerFailing_doesNotStopTheRest() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        when(caseRepository.findUnansweredDueBy(any(), any()))
                .thenThrow(new RuntimeException("schema down"))
                .thenReturn(List.of(caseDue(3L, TODAY)));

        scheduler.sweepDeadlines();

        // The second insurer still gets swept and its critical case notified.
        verify(analystNotificationService).notifyDeadline(any(), eq(DeadlinePriority.CRITICAL), eq(TODAY));
    }

    @Test
    void noInsurers_doesNothing() {
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of());

        scheduler.sweepDeadlines();

        verify(caseRepository, never()).findUnansweredDueBy(any(), any());
        verifyNoInteractions(analystNotificationService);
    }

    private Case caseDue(Long id, LocalDate deadline) {
        return Case.builder().id(id).responseDeadline(deadline).build();
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
