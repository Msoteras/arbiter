package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.NotificationResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.NotificationNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseNotificationServiceTest {

    private static final String INSURED_EMAIL = "martina@example.com";
    private static final String ACCOUNT_EMAIL = "martina.cuenta@example.com";

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private SendGridAdapter sendGridAdapter;

    @InjectMocks
    private CaseNotificationService service;

    @AfterEach
    void clearRequestState() {
        SecurityContextHolder.clearContext();
        CallerContext.clear();
        TenantContext.clear();
    }

    @Test
    void notifyStatusChange_writesTheRowAndSendsTheEmail() {
        savesWhatItIsGiven();
        when(sendGridAdapter.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.notifyStatusChange(caseWith(insuredWith(INSURED_EMAIL)), CaseStatus.APPROVED);

        Notification saved = firstSaved();
        assertThat(saved.getType()).isEqualTo("APPROVED");
        assertThat(saved.getRecipientId()).isEqualTo(7L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.isSent()).isTrue();
        assertThat(saved.getSentAt()).isNotNull();
        verify(sendGridAdapter).send(eq(INSURED_EMAIL), anyString(), anyString());
    }

    /**
     * Regression: with no API key the adapter logs and returns without sending, and the row used to
     * be stamped sent=true anyway — the panel showed a mail the insured never got.
     */
    @Test
    void notifyStatusChange_doesNotMarkSentWhenNothingWentOut() {
        savesWhatItIsGiven();
        when(sendGridAdapter.send(anyString(), anyString(), anyString())).thenReturn(false);

        service.notifyStatusChange(caseWith(insuredWith(INSURED_EMAIL)), CaseStatus.APPROVED);

        assertThat(firstSaved().isSent()).isFalse();
        assertThat(firstSaved().getSentAt()).isNull();
    }

    /** Only what asks the insured for something or is the outcome; the rest is internal traffic. */
    @Test
    void notifyStatusChange_staysSilentForInternalStatuses() {
        service.notifyStatusChange(caseWith(insuredWith(INSURED_EMAIL)),
                CaseStatus.PENDING_ANALYST_REVIEW);

        verifyNoInteractions(notificationRepository, sendGridAdapter);
    }

    /**
     * The row is the record of the attempt: it survives a failed send so the panel still shows the
     * notification and a retry can find what never went out.
     */
    @Test
    void notifyStatusChange_keepsTheRowWhenTheEmailFails() {
        savesWhatItIsGiven();
        doThrow(new RuntimeException("SendGrid caído"))
                .when(sendGridAdapter).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.notifyStatusChange(
                caseWith(insuredWith(INSURED_EMAIL)), CaseStatus.REJECTED))
                .doesNotThrowAnyException();

        assertThat(firstSaved().isSent()).isFalse();
    }

    /**
     * Regression: a missing mail SDK surfaces as NoClassDefFoundError, which is an Error and not an
     * Exception. It escaped as a 500 on an approval that had already been applied.
     */
    @Test
    void notifyStatusChange_survivesAnErrorAndNotJustAnException() {
        savesWhatItIsGiven();
        doThrow(new NoClassDefFoundError("com/sendgrid/helpers/mail/Mail"))
                .when(sendGridAdapter).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.notifyStatusChange(
                caseWith(insuredWith(INSURED_EMAIL)), CaseStatus.APPROVED))
                .doesNotThrowAnyException();
    }

    /** {@code insured} is a snapshot of the insurer's DB, so its email can be stale or absent. */
    @Test
    void notifyStatusChange_fallsBackToTheAccountEmail() {
        savesWhatItIsGiven();
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(User.builder().id(7L).email(ACCOUNT_EMAIL).build()));

        service.notifyStatusChange(caseWith(insuredWith("  ")), CaseStatus.APPROVED);

        verify(sendGridAdapter).send(eq(ACCOUNT_EMAIL), anyString(), anyString());
    }

    /** recipient_id is NOT NULL, so an insured who never signed up can't have a row. */
    @Test
    void notifyStatusChange_skipsAnInsuredWithoutAnAccount() {
        Insured withoutAccount = Insured.builder().id(1L).email(INSURED_EMAIL).build();

        service.notifyStatusChange(caseWith(withoutAccount), CaseStatus.APPROVED);

        verifyNoInteractions(notificationRepository, sendGridAdapter);
    }

    @Test
    void markRead_refusesANotificationOfSomeoneElse() {
        authenticatedAs(ACCOUNT_EMAIL);
        when(notificationRepository.findByIdAndRecipientId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(99L, null))
                .isInstanceOf(NotificationNotFoundException.class);
        verify(notificationRepository, never()).save(any());
    }

    /** Opening the panel twice isn't an error, and the timestamp keeps the first time it was seen. */
    @Test
    void markRead_isIdempotent() {
        authenticatedAs(ACCOUNT_EMAIL);
        Instant firstSeen = Instant.parse("2026-08-01T10:00:00Z");
        Notification alreadyRead = Notification.builder().id(5L).read(true).readAt(firstSeen).build();
        when(notificationRepository.findByIdAndRecipientId(5L, 7L))
                .thenReturn(Optional.of(alreadyRead));

        service.markRead(5L, null);

        assertThat(alreadyRead.getReadAt()).isEqualTo(firstSeen);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAllRead_stampsEveryUnreadOneAtOnce() {
        authenticatedAs(ACCOUNT_EMAIL);
        Notification one = Notification.builder().id(1L).read(false).build();
        Notification two = Notification.builder().id(2L).read(false).build();
        when(notificationRepository.findByRecipientIdAndReadFalse(7L)).thenReturn(List.of(one, two));

        service.markAllRead();

        assertThat(one.isRead()).isTrue();
        assertThat(two.isRead()).isTrue();
        assertThat(one.getReadAt()).isEqualTo(two.getReadAt());
        verify(notificationRepository).saveAll(List.of(one, two));
    }

    /** Reading only the active tenant left a multi-insurer insured with a bell counting one. */
    @Test
    void forCurrentUser_mergesEveryInsurerOfTheInsured() {
        insuredOfBothInsurers();
        when(notificationRepository
                .findByRecipientIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(7L), any(), any()))
                .thenAnswer(invocation -> "arbiter_provincia".equals(TenantContext.get())
                        ? List.of(daysAgo(1), daysAgo(2), daysAgo(3))
                        : List.of(daysAgo(10), daysAgo(11), daysAgo(12)));

        List<NotificationResponse> merged = service.forCurrentUser();

        assertThat(merged).hasSize(6);
        // Provincia's are newer and come first even though BBVA is read before it.
        assertThat(merged.getFirst().insurerSlug()).isEqualTo("provincia");
        assertThat(merged.getLast().insurerSlug()).isEqualTo("bbva");
    }

    /** The cap is global, not per schema: each one may bring up to the limit on its own. */
    @Test
    void forCurrentUser_capsAcrossSchemas() {
        insuredOfBothInsurers();
        when(notificationRepository
                .findByRecipientIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(7L), any(), any()))
                .thenAnswer(invocation -> "arbiter_provincia".equals(TenantContext.get())
                        ? List.of(daysAgo(1), daysAgo(2), daysAgo(3), daysAgo(4), daysAgo(5), daysAgo(6))
                        : List.of(daysAgo(7), daysAgo(8), daysAgo(9), daysAgo(10), daysAgo(11), daysAgo(12)));

        List<NotificationResponse> shown = service.forCurrentUser();

        assertThat(shown).hasSize(6);
        // The 6 newest are all Provincia's: the cut is by global date, not one slot per schema.
        assertThat(shown).allMatch(item -> "provincia".equals(item.insurerSlug()));
    }

    @Test
    void unreadCount_addsUpEveryInsurer() {
        insuredOfBothInsurers();
        when(notificationRepository.countByRecipientIdAndReadFalseAndCreatedAtAfter(eq(7L), any()))
                .thenAnswer(invocation -> "arbiter_provincia".equals(TenantContext.get()) ? 2L : 1L);

        assertThat(service.unreadCountForCurrentUser()).isEqualTo(3L);
    }

    /** The badge never goes above what the panel can list. */
    @Test
    void unreadCount_neverExceedsThePanelCap() {
        insuredOfBothInsurers();
        when(notificationRepository.countByRecipientIdAndReadFalseAndCreatedAtAfter(eq(7L), any()))
                .thenReturn(20L);

        assertThat(service.unreadCountForCurrentUser()).isEqualTo(6L);
    }

    /** The request tenant is restored, or the connection returns to the pool on another schema. */
    @Test
    void reading_restoresTheRequestTenant() {
        insuredOfBothInsurers();
        when(notificationRepository.countByRecipientIdAndReadFalseAndCreatedAtAfter(eq(7L), any()))
                .thenReturn(0L);

        service.unreadCountForCurrentUser();

        assertThat(TenantContext.get()).isEqualTo("arbiter_bbva");
    }

    /** Relative to today: against a moving window, a literal date falls out on its own. */
    private Notification daysAgo(int days) {
        return notificationAt(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private void insuredOfBothInsurers() {
        authenticatedAs(ACCOUNT_EMAIL);
        TenantContext.set("arbiter_bbva");
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), "arbiter_bbva"));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                Insurer.builder().id(1L).name("BBVA").schemaName("arbiter_bbva").active(true).build(),
                Insurer.builder().id(2L).name("Provincia").schemaName("arbiter_provincia").active(true).build()));
    }

    private Notification notificationAt(Instant createdAt) {
        return Notification.builder().id(1L).type("APPROVED").createdAt(createdAt).build();
    }

    /**
     * La reapertura no se puede distinguir por el estado destino (una clasificación normal llega
     * al mismo PENDING_ANALYST_REVIEW), así que tiene su propia entrada y su propio `type` — si
     * reusara el del estado, el panel del asegurado diría "en revisión" sobre un expediente que
     * en realidad se reabrió.
     */
    @Test
    void notifyReopened_writesItsOwnTypeAndSendsTheEmail() {
        savesWhatItIsGiven();
        when(sendGridAdapter.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.notifyReopened(caseWith(insuredWith(INSURED_EMAIL)));

        Notification saved = firstSaved();
        assertThat(saved.getType()).isEqualTo("REOPENED");
        assertThat(saved.isSent()).isTrue();
        verify(sendGridAdapter).send(eq(INSURED_EMAIL), anyString(), anyString());
    }

    /**
     * El motivo que escribe el analista es interno (puede nombrar una sospecha, un error, una pista
     * de fraude). Al asegurado se le cuenta el hecho, nunca el porqué —
     * [[project-asegurado-vs-analista-visibility]].
     */
    @Test
    void notifyReopened_saysNothingAboutWhy() {
        savesWhatItIsGiven();
        when(sendGridAdapter.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.notifyReopened(caseWith(insuredWith(INSURED_EMAIL)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sendGridAdapter).send(anyString(), anyString(), body.capture());
        assertThat(body.getValue().toLowerCase())
                .doesNotContain("fraude", "riesgo", "sospech", "clasificac", "motivo");
    }

    private void savesWhatItIsGiven() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Notification firstSaved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().getFirst();
    }

    private void authenticatedAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", List.of()));
        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(User.builder().id(7L).email(email).build()));
    }

    private Insured insuredWith(String email) {
        return Insured.builder()
                .id(1L)
                .email(email)
                .user(User.builder().id(7L).email(ACCOUNT_EMAIL).build())
                .build();
    }

    private Case caseWith(Insured insured) {
        return Case.builder().id(42L).insured(insured).build();
    }
}
