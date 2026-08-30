package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageThreadResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ClosedConversationException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseMessageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La conversación del expediente. Lo que se prueba es de quién es cada mensaje, quién lo tiene sin
 * leer, y hasta cuándo se puede escribir — las tres cosas dependen de quién pregunta, así que un
 * error acá no se ve en pantalla: se ve como el hilo de otro.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseMessageServiceTest {

    private static final Long CASE_ID = 7L;
    private static final Long STATE_ID = 3L;
    private static final String OWNER_DNI = "40.123.456";
    private static final String ANALYST_EMAIL = "analista.arbiter@gmail.com";
    private static final String INSURED_EMAIL = "asegurado.arbiter@gmail.com";

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Mock
    private CaseMessageRepository messageRepository;
    @Mock
    private CaseRepository caseRepository;
    @Mock
    private CaseStatusHistoryRepository statusHistoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private InsurerTenantScope tenantScope;
    @Mock
    private MessageNotificationService notificationService;

    private final CaseAccessPolicy accessPolicy = new CaseAccessPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CaseMessageService service;

    @BeforeEach
    void setUp() {
        service = new CaseMessageService(messageRepository, caseRepository, statusHistoryRepository,
                userRepository, accessPolicy, tenantScope, notificationService, clock);
        ReflectionTestUtils.setField(service, "replyWindowDays", 7);

        when(tenantScope.forCase(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
        when(userRepository.findByEmail(ANALYST_EMAIL))
                .thenReturn(Optional.of(User.builder().id(2L).email(ANALYST_EMAIL).build()));
        when(userRepository.findByEmail(INSURED_EMAIL))
                .thenReturn(Optional.of(User.builder().id(1L).email(INSURED_EMAIL).build()));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        CallerContext.clear();
    }

    // ----- lectura -----

    @Test
    void insuredReadingSomeoneElsesThread_getsA404() {
        asInsured("11.222.333");
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);

        assertThatThrownBy(() -> service.thread(CASE_ID, null))
                .isInstanceOf(CaseNotFoundException.class);
        verify(messageRepository, never()).findByCaseIdOrderByCreatedAtAsc(any());
    }

    @Test
    void threadIsToldApartFromEachSide() {
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);
        when(messageRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID)).thenReturn(List.of(
                message(StatusChangeActor.ANALYST, "¿Tenés la factura?", null),
                message(StatusChangeActor.INSURED, "La subo hoy", null)));

        asAnalyst();
        CaseMessageThreadResponse forAnalyst = service.thread(CASE_ID, null);
        assertThat(forAnalyst.messages()).extracting(CaseMessageResponse::mine)
                .containsExactly(true, false);
        assertThat(forAnalyst.unread()).isEqualTo(1);

        asInsured(OWNER_DNI);
        CaseMessageThreadResponse forInsured = service.thread(CASE_ID, null);
        assertThat(forInsured.messages()).extracting(CaseMessageResponse::mine)
                .containsExactly(false, true);
        assertThat(forInsured.unread()).isEqualTo(1);
    }

    /** El referente ve el hilo como ve la bandeja: mira, no escribe, y no cuenta no leídos. */
    @Test
    void referentReadsButCannotPost() {
        authenticate("ROLE_REFERENTE_ASEGURADORA", "referente.arbiter@gmail.com", null);
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);
        when(messageRepository.findByCaseIdOrderByCreatedAtAsc(CASE_ID)).thenReturn(List.of(
                message(StatusChangeActor.INSURED, "¿Cómo viene?", null)));

        CaseMessageThreadResponse thread = service.thread(CASE_ID, null);

        assertThat(thread.canPost()).isFalse();
        assertThat(thread.unread()).isZero();
        assertThat(thread.messages()).singleElement()
                .extracting(CaseMessageResponse::mine).isEqualTo(false);
    }

    // ----- la ventana de respuesta -----

    @Test
    void resolvedCaseStillTakesMessagesInsideTheWindow() {
        givenResolvedCase(NOW.minus(3, ChronoUnit.DAYS));
        asInsured(OWNER_DNI);

        assertThat(service.thread(CASE_ID, null).canPost()).isTrue();
        assertThat(service.post(CASE_ID, null, "No estoy de acuerdo con el rechazo").body())
                .isEqualTo("No estoy de acuerdo con el rechazo");
    }

    @Test
    void resolvedCasePastTheWindowIsClosed() {
        givenResolvedCase(NOW.minus(10, ChronoUnit.DAYS));
        asInsured(OWNER_DNI);

        CaseMessageThreadResponse thread = service.thread(CASE_ID, null);
        assertThat(thread.canPost()).isFalse();
        assertThat(thread.closedNotice()).contains("7 días");

        assertThatThrownBy(() -> service.post(CASE_ID, null, "Tarde"))
                .isInstanceOf(ClosedConversationException.class);
        verify(messageRepository, never()).save(any());
    }

    /**
     * Fechado por la transición al estado final y no por {@code updatedAt}: cualquier escritura
     * posterior (una sync, una reclasificación) correría la fecha y reabriría el hilo sola.
     */
    @Test
    void windowIsCountedFromTheTransition_notFromUpdatedAt() {
        Case resolved = givenResolvedCase(NOW.minus(10, ChronoUnit.DAYS));
        resolved.setUpdatedAt(NOW.minus(1, ChronoUnit.DAYS));
        asInsured(OWNER_DNI);

        assertThat(service.thread(CASE_ID, null).canPost()).isFalse();
    }

    // ----- escritura y avisos -----

    @Test
    void postingAttributesTheMessageToTheCallersSide() {
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);
        asAnalyst();

        service.post(CASE_ID, null, "  Necesito la factura  ");

        ArgumentCaptor<CaseMessage> saved = ArgumentCaptor.forClass(CaseMessage.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getSenderRole()).isEqualTo(StatusChangeActor.ANALYST);
        assertThat(saved.getValue().getBody()).isEqualTo("Necesito la factura");
        verify(notificationService).notifyNewMessage(any(), eq(StatusChangeActor.ANALYST));
    }

    /** Diez mensajes seguidos no son diez mails: se avisa una vez por racha sin leer. */
    @Test
    void aSecondMessageWhileTheFirstIsUnreadDoesNotNotifyAgain() {
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);
        asAnalyst();
        when(messageRepository.existsByCaseIdAndSenderRoleAndReadAtIsNull(
                CASE_ID, StatusChangeActor.ANALYST)).thenReturn(true);

        service.post(CASE_ID, null, "¿Y la factura?");

        verify(notificationService, never()).notifyNewMessage(any(), any());
    }

    @Test
    void insuredCannotPostToSomeoneElsesCase() {
        asInsured("11.222.333");
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);

        assertThatThrownBy(() -> service.post(CASE_ID, null, "Hola"))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void referentCannotPost() {
        authenticate("ROLE_REFERENTE_ASEGURADORA", "referente.arbiter@gmail.com", null);
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);

        assertThatThrownBy(() -> service.post(CASE_ID, null, "Che"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ----- leídos -----

    @Test
    void markReadOnlyTouchesTheOtherSidesMessages() {
        givenCase(CaseStatus.PENDING_ANALYST_REVIEW);
        asAnalyst();
        CaseMessage incoming = message(StatusChangeActor.INSURED, "Ya la subí", null);
        when(messageRepository.findByCaseIdAndSenderRoleAndReadAtIsNull(
                CASE_ID, StatusChangeActor.INSURED)).thenReturn(List.of(incoming));

        service.markRead(CASE_ID, null);

        assertThat(incoming.getReadAt()).isEqualTo(NOW);
        verify(messageRepository, never()).findByCaseIdAndSenderRoleAndReadAtIsNull(
                CASE_ID, StatusChangeActor.ANALYST);
    }

    // ----- helpers -----

    private Case givenCase(CaseStatus status) {
        CaseState state = CaseStates.of(status);
        state.setId(STATE_ID);
        Case caseRecord = new Case();
        caseRecord.setId(CASE_ID);
        caseRecord.setInsured(CaseFixtures.insured(OWNER_DNI, "Martina", "Soteras"));
        caseRecord.setCurrentStatus(state);
        caseRecord.setUpdatedAt(NOW);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        return caseRecord;
    }

    private Case givenResolvedCase(Instant resolvedAt) {
        Case caseRecord = givenCase(CaseStatus.REJECTED);
        when(statusHistoryRepository.findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(
                CASE_ID, STATE_ID))
                .thenReturn(Optional.of(CaseStatusHistory.builder().changedAt(resolvedAt).build()));
        return caseRecord;
    }

    private CaseMessage message(StatusChangeActor sender, String body, Instant readAt) {
        return CaseMessage.builder()
                .caseId(CASE_ID)
                .senderRole(sender)
                .body(body)
                .createdAt(NOW)
                .readAt(readAt)
                .build();
    }

    private void asAnalyst() {
        authenticate("ROLE_ANALISTA_SINIESTROS", ANALYST_EMAIL, null);
    }

    private void asInsured(String dni) {
        authenticate("ROLE_ASEGURADO", INSURED_EMAIL, dni);
    }

    private void authenticate(String role, String email, String dni) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a",
                        List.of(new SimpleGrantedAuthority(role))));
        CallerContext.set(new CallerContext.Caller(dni, List.of(1L), "arbiter_bbva"));
    }
}
