package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "arbiter.messaging.allowed-origins=https://arbiter.example,http://localhost:4200")
class WebSocketOriginTests extends AbstractPersistenceIT {

    @LocalServerPort
    private int port;

    @Test
    void theFrontendOrigin_opensTheSocketThroughAProxy() throws Exception {
        assertThat(handshake("https://arbiter.example")).isEqualTo(101);
    }

    @Test
    void everyConfiguredOriginIsAccepted() throws Exception {
        assertThat(handshake("http://localhost:4200")).isEqualTo(101);
    }

    @Test
    void anyOtherOrigin_isRejected() throws Exception {
        assertThat(handshake("https://evil.example")).isEqualTo(403);
    }

    private int handshake(String origin) throws Exception {
        try {
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/ws"), new WebSocket.Listener() {})
                    .get(10, TimeUnit.SECONDS);
            socket.abort();
            return 101;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException rejected) {
                return rejected.getResponse().statusCode();
            }
            throw e;
        }
    }
}
