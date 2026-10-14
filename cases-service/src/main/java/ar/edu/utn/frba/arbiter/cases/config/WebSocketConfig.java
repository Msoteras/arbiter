package ar.edu.utn.frba.arbiter.cases.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Subscribe-only: there is no application destination, so writing stays on
 * {@code POST /cases/{id}/messages}, which owns the access checks.
 *
 * <p>Two brokers. With a relay host configured, subscriptions live in an external STOMP broker
 * (RabbitMQ) and a message posted on one instance reaches the sockets held by any other. Without
 * one the broker is in memory, which needs nothing running but bounds this service to a single
 * instance.
 *
 * <p>Origins are explicit: behind nginx and Railway's TLS the handshake never looks same-origin.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String TOPIC_PREFIX = "/topic";

    private final StompAuthChannelInterceptor authInterceptor;
    private final String[] allowedOrigins;
    private final String relayHost;
    private final int relayPort;
    private final String relayLogin;
    private final String relayPasscode;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           @Value("${arbiter.messaging.allowed-origins}") String[] allowedOrigins,
                           @Value("${arbiter.messaging.relay.host:}") String relayHost,
                           @Value("${arbiter.messaging.relay.port:61613}") int relayPort,
                           @Value("${arbiter.messaging.relay.login:guest}") String relayLogin,
                           @Value("${arbiter.messaging.relay.passcode:guest}") String relayPasscode) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.relayLogin = relayLogin;
        this.relayPasscode = relayPasscode;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/v1/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        if (relayHost.isBlank()) {
            registry.enableSimpleBroker(TOPIC_PREFIX);
            return;
        }
        // Client sessions log in with the service's credentials too: who may connect and follow a
        // thread is decided by StompAuthChannelInterceptor, and the broker is never reachable
        // from outside the private network.
        registry.enableStompBrokerRelay(TOPIC_PREFIX)
                .setRelayHost(relayHost)
                .setRelayPort(relayPort)
                .setSystemLogin(relayLogin)
                .setSystemPasscode(relayPasscode)
                .setClientLogin(relayLogin)
                .setClientPasscode(relayPasscode);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
