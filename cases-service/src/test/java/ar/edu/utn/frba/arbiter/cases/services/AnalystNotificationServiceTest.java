package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalystNotificationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;
    @Mock
    private SendGridAdapter sendGridAdapter;

    private AnalystNotificationService service;

    @BeforeEach
    void setUp() {
        service = new AnalystNotificationService(
                notificationRepository, claimsAnalystRepository, sendGridAdapter);
        lenient().when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void nonNotifiablePriority_doesNothing() {
        service.notifyDeadline(caseWith(null, TODAY.plusDays(7)), DeadlinePriority.WATCH, TODAY);

        verifyNoInteractions(notificationRepository, claimsAnalystRepository, sendGridAdapter);
    }

    @Test
    void critical_assignedAnalyst_notifiesOnlyThatAnalyst() {
        ClaimsAnalyst assigned = analyst(10L, 100L, "ana@aseg.com");
        Case caseRecord = caseWith(assigned, TODAY.plusDays(1));

        service.notifyDeadline(caseRecord, DeadlinePriority.CRITICAL, TODAY);

        verify(claimsAnalystRepository, never()).findAll();
        // notifyOne guarda dos veces (crea la fila y la re-guarda con sent=true); es la misma
        // instancia, así que basta capturar y mirar el valor. El único destinatario ⇒ un solo send.
        verify(sendGridAdapter).send(eq("ana@aseg.com"), anyString(), anyString());
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getRecipientId()).isEqualTo(100L);
        assertThat(saved.getValue().getType()).isEqualTo("DEADLINE_CRITICAL");
    }

    @Test
    void critical_unassigned_notifiesEveryAnalyst() {
        when(claimsAnalystRepository.findAll()).thenReturn(List.of(
                analyst(1L, 101L, "a@aseg.com"),
                analyst(2L, 102L, "b@aseg.com")));

        service.notifyDeadline(caseWith(null, TODAY.plusDays(2)), DeadlinePriority.CRITICAL, TODAY);

        // Un send por analista del equipo — la señal inequívoca de "se notificó a todos".
        verify(sendGridAdapter).send(eq("a@aseg.com"), anyString(), anyString());
        verify(sendGridAdapter).send(eq("b@aseg.com"), anyString(), anyString());
    }

    @Test
    void alreadyNotifiedAtThisLevel_isSkipped() {
        ClaimsAnalyst assigned = analyst(10L, 100L, "ana@aseg.com");
        when(notificationRepository.existsByCaseEntityIdAndRecipientIdAndType(
                eq(1L), eq(100L), eq("DEADLINE_CRITICAL"))).thenReturn(true);

        service.notifyDeadline(caseWith(assigned, TODAY.plusDays(1)), DeadlinePriority.CRITICAL, TODAY);

        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(sendGridAdapter);
    }

    @Test
    void overdue_usesADistinctType_soItEscalatesAfterCritical() {
        ClaimsAnalyst assigned = analyst(10L, 100L, "ana@aseg.com");

        service.notifyDeadline(caseWith(assigned, TODAY.minusDays(3)), DeadlinePriority.OVERDUE, TODAY);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("DEADLINE_OVERDUE");
    }

    @Test
    void analystWithoutEmail_recordsTheRowButSendsNoMail() {
        ClaimsAnalyst assigned = analyst(10L, 100L, "  ");

        service.notifyDeadline(caseWith(assigned, TODAY.plusDays(1)), DeadlinePriority.CRITICAL, TODAY);

        verify(notificationRepository, atLeastOnce()).save(any());
        verifyNoInteractions(sendGridAdapter);
    }

    @Test
    void sendFailure_doesNotPropagate() {
        ClaimsAnalyst assigned = analyst(10L, 100L, "ana@aseg.com");
        org.mockito.Mockito.doThrow(new RuntimeException("SendGrid 500"))
                .when(sendGridAdapter).send(anyString(), anyString(), anyString());

        // No debe romper el barrido: la fila queda (sent=false) y la excepción se traga.
        service.notifyDeadline(caseWith(assigned, TODAY.plusDays(1)), DeadlinePriority.CRITICAL, TODAY);

        verify(notificationRepository, atLeastOnce()).save(any());
    }

    // ─────────── fixtures ───────────

    private Case caseWith(ClaimsAnalyst analyst, LocalDate deadline) {
        Insured insured = new Insured();
        insured.setName("Laura");
        insured.setSurname("Fernández");
        ClaimCause cause = new ClaimCause();
        cause.setName("Robo en vía pública");
        return Case.builder()
                .id(1L)
                .analyst(analyst)
                .insured(insured)
                .claimCause(cause)
                .responseDeadline(deadline)
                .build();
    }

    private ClaimsAnalyst analyst(Long id, Long userId, String email) {
        User user = new User();
        user.setId(userId);
        return ClaimsAnalyst.builder()
                .id(id)
                .name("A")
                .surname("Nalista")
                .email(email)
                .user(user)
                .build();
    }
}
