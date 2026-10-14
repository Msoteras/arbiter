package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.cases.CasesServiceApplication;
import ar.edu.utn.frba.arbiter.cases.services.CaseTopic;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Two instances of the service behind one RabbitMQ: a message published on one reaches a socket
 * held by the other, which the in-memory broker cannot do.
 *
 * <p>Who may subscribe is {@link StompAuthChannelInterceptorTest}'s subject; here the interceptor
 * lets everything through so the test needs no case to follow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatRelayTests extends AbstractPersistenceIT {

    private static final int STOMP_PORT = 61613;
    private static final String LOGIN = "arbiter";
    private static final String TOPIC = CaseTopic.of("arbiter_bbva", 7L);

    // Same broker as rabbitmq/Dockerfile: the stock image with the STOMP plugin switched on.
    static final GenericContainer<?> RABBITMQ = new GenericContainer<>("rabbitmq:4-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", LOGIN)
            .withEnv("RABBITMQ_DEFAULT_PASS", LOGIN)
            .withCommand("sh", "-c",
                    "rabbitmq-plugins enable --offline rabbitmq_stomp && exec docker-entrypoint.sh rabbitmq-server")
            .withExposedPorts(STOMP_PORT)
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void relay(DynamicPropertyRegistry registry) {
        relayProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    private static Map<String, Object> relayProperties() {
        return Map.of(
                "arbiter.messaging.relay.host", RABBITMQ.getHost(),
                "arbiter.messaging.relay.port", RABBITMQ.getMappedPort(STOMP_PORT),
                "arbiter.messaging.relay.login", LOGIN,
                "arbiter.messaging.relay.passcode", LOGIN);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcConnectionDetails database;
    @MockitoBean
    private StompAuthChannelInterceptor authInterceptor;

    private ConfigurableApplicationContext otherInstance;

    @BeforeEach
    void startTheOtherInstance() {
        when(authInterceptor.preSend(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> properties = new HashMap<>(relayProperties());
        properties.put("server.port", 0);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "update");
        properties.put("arbiter.auth.jwt.secret", "test-secret-at-least-32-bytes-long-for-hs256");

        // As arguments: SpringApplicationBuilder.properties() are defaults, and application.yml wins over those.
        otherInstance = new SpringApplicationBuilder(CasesServiceApplication.class)
                .run(properties.entrySet().stream()
                        .map(property -> "--" + property.getKey() + "=" + property.getValue())
                        .toArray(String[]::new));
    }

    @AfterEach
    void stopTheOtherInstance() {
        otherInstance.close();
    }

    @Test
    void aMessagePublishedOnOneInstance_reachesASocketHeldByTheOther() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        StompSession session = connect();
        session.subscribe(TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });

        SimpMessagingTemplate publisher = otherInstance.getBean(SimpMessagingTemplate.class);
        String delivered = null;
        // Republished until it lands: neither the subscription nor the publisher's connection to
        // the broker is acknowledged, so the first attempts may be sent before they are in place.
        for (int attempt = 0; attempt < 40 && delivered == null; attempt++) {
            try {
                publisher.convertAndSend(TOPIC, (Object) Map.of("body", "hola desde la otra instancia"));
            } catch (MessagingException brokerNotReadyYet) {
                // retried below
            }
            delivered = received.poll(500, TimeUnit.MILLISECONDS);
        }

        assertThat(delivered).contains("hola desde la otra instancia");
        session.disconnect();
    }

    private StompSession connect() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:4200");
        return client.connectAsync("ws://localhost:" + port + "/api/v1/ws", handshake, new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                .get(20, TimeUnit.SECONDS);
    }
}
