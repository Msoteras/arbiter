package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.services.CaseAccessPolicy;
import ar.edu.utn.frba.arbiter.cases.services.InsurerTenantScope;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Quién puede engancharse a la conversación de un expediente por el socket. Es la superficie más
 * fácil de dejar abierta: los dos filtros de servlet no corren acá, así que sin este interceptor
 * cualquier sesión autenticada podría suscribirse a los ids uno por uno y leer todos los hilos del
 * tenant. Lo que se prueba es que <b>niegue</b>.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StompAuthChannelInterceptorTest {

    private static final String SECRET = "una-clave-de-prueba-lo-bastante-larga-para-hmac-sha256!!";
    private static final Long CASE_ID = 7L;
    private static final String OWNER_DNI = "40.123.456";

    @Mock
    private CaseRepository caseRepository;
    @Mock
    private InsurerTenantScope tenantScope;

    private final SecretKey key = JwtSupport.key(SECRET);
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(
                key, caseRepository, new CaseAccessPolicy(), tenantScope);
        when(tenantScope.forCase(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());

        Case caseRecord = new Case();
        caseRecord.setId(CASE_ID);
        caseRecord.setInsured(CaseFixtures.insured(OWNER_DNI, "Martina", "Soteras"));
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
    }

    @AfterEach
    void clearRequestState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CallerContext.clear();
    }

    // ----- conexión -----

    @Test
    void connectWithoutAToken_isRejected() {
        assertThatThrownBy(() -> send(connect(null)))
                .isInstanceOf(StompAccessDeniedException.class);
    }

    @Test
    void connectWithATokenSignedBySomeoneElse_isRejected() {
        String foreign = Jwts.builder().subject("intruso@example.com")
                .signWith(JwtSupport.key("otra-clave-igual-de-larga-pero-que-no-es-la-nuestra!!"))
                .compact();

        assertThatThrownBy(() -> send(connect("Bearer " + foreign)))
                .isInstanceOf(StompAccessDeniedException.class);
    }

    @Test
    void connectWithAValidToken_passes() {
        assertThatCode(() -> send(connect("Bearer " + tokenFor("ASEGURADO", OWNER_DNI))))
                .doesNotThrowAnyException();
    }

    // ----- suscripción -----

    @Test
    void insuredSubscribingToSomeoneElsesCase_isRejected() {
        Map<String, Object> session = sessionOf("ASEGURADO", "11.222.333");

        assertThatThrownBy(() -> send(subscribe("/topic/cases/bbva/" + CASE_ID, session)))
                .isInstanceOf(StompAccessDeniedException.class);
    }

    @Test
    void insuredSubscribingToTheirOwnCase_passes() {
        Map<String, Object> session = sessionOf("ASEGURADO", OWNER_DNI);

        assertThatCode(() -> send(subscribe("/topic/cases/bbva/" + CASE_ID, session)))
                .doesNotThrowAnyException();
    }

    @Test
    void analystSubscribingToAnyCaseOfTheirTenant_passes() {
        Map<String, Object> session = sessionOf("ANALISTA_SINIESTROS", null);

        assertThatCode(() -> send(subscribe("/topic/cases/bbva/" + CASE_ID, session)))
                .doesNotThrowAnyException();
    }

    /** Un destino que no es un hilo de expediente no tiene por qué existir. */
    @Test
    void subscribingToAnythingElse_isRejected() {
        Map<String, Object> session = sessionOf("ANALISTA_SINIESTROS", null);

        assertThatThrownBy(() -> send(subscribe("/topic/cases/bbva/no-es-un-id", session)))
                .isInstanceOf(StompAccessDeniedException.class);
        assertThatThrownBy(() -> send(subscribe("/topic/otra-cosa", session)))
                .isInstanceOf(StompAccessDeniedException.class);
    }

    /** Sin pasar por CONNECT no hay identidad que evaluar. */
    @Test
    void subscribingWithoutHavingConnected_isRejected() {
        assertThatThrownBy(() -> send(subscribe("/topic/cases/bbva/" + CASE_ID, new HashMap<>())))
                .isInstanceOf(StompAccessDeniedException.class);
    }

    // ----- helpers -----

    private void send(Message<byte[]> message) {
        interceptor.preSend(message, null);
    }

    private Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setSessionAttributes(new HashMap<>());
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribe(String destination, Map<String, Object> session) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(session);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** El estado que deja un CONNECT válido, para no reconstruirlo a mano en cada test. */
    private Map<String, Object> sessionOf(String rol, String dni) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        Map<String, Object> session = new HashMap<>();
        accessor.setSessionAttributes(session);
        accessor.setNativeHeader("Authorization", "Bearer " + tokenFor(rol, dni));
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
        return session;
    }

    private String tokenFor(String rol, String dni) {
        var builder = Jwts.builder()
                .subject("alguien@example.com")
                .claim("rol", rol)
                .claim("tenantSchema", "arbiter_bbva")
                .claim("insurerIds", List.of(1))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key);
        if (dni != null) {
            builder.claim("insuredId", dni);
        }
        return builder.compact();
    }
}
