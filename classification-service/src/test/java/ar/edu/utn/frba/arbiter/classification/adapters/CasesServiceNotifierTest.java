package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished.Outcome;
import ar.edu.utn.frba.arbiter.common.http.ConnectionRetryInterceptor;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CasesServiceNotifierTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    private final CopyOnWriteArrayList<Request> received = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsTheOutcome_withAServiceTokenForTheCaseTenant() throws IOException {
        server = startServer(0, 204);
        TenantContext.set("arbiter_bbva");

        notifierPointingAt(baseUrl()).classificationFinished(42L, Outcome.COMPLETED);

        assertThat(received).hasSize(1);
        Request request = received.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/cases/42/classification-finished");
        assertThat(request.body()).isEqualToIgnoringWhitespace("{\"outcome\":\"COMPLETED\"}");
        Claims claims = Jwts.parser().verifyWith(JwtSupport.key(JWT_SECRET)).build()
                .parseSignedClaims(request.authorization().substring("Bearer ".length()))
                .getPayload();
        assertThat(claims.get("rol")).isNull();
        assertThat(claims.get("tenantSchema")).isEqualTo("arbiter_bbva");
    }

    @Test
    void casesServiceAnsweringAnError_isLoggedNotThrown() throws IOException {
        server = startServer(0, 500);

        assertThatCode(() -> notifierPointingAt(baseUrl()).classificationFinished(42L, Outcome.FAILED))
                .doesNotThrowAnyException();
        assertThat(received).hasSize(1);
    }

    @Test
    void casesServiceUnreachable_isLoggedNotThrown() {
        CasesServiceNotifier notifier = notifierPointingAt("http://localhost:" + closedPort());

        assertThatCode(() -> notifier.classificationFinished(42L, Outcome.COMPLETED)).doesNotThrowAnyException();
    }

    @Test
    void casesServiceStillBooting_isWaitedFor() throws Exception {
        int port = closedPort();
        CasesServiceNotifier notifier = new CasesServiceNotifier("http://localhost:" + port, JWT_SECRET,
                new ConnectionRetryInterceptor(10, Duration.ofMillis(100), Duration.ofMillis(100)));
        AtomicInteger bootFailures = new AtomicInteger();
        Thread booting = new Thread(() -> {
            try {
                Thread.sleep(300);
                server = startServer(port, 204);
            } catch (IOException | InterruptedException e) {
                bootFailures.incrementAndGet();
            }
        });
        booting.start();

        notifier.classificationFinished(42L, Outcome.COMPLETED);

        booting.join();
        assertThat(bootFailures).hasValue(0);
        assertThat(received).hasSize(1);
    }

    private CasesServiceNotifier notifierPointingAt(String url) {
        return new CasesServiceNotifier(url, JWT_SECRET,
                new ConnectionRetryInterceptor(2, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpServer startServer(int port, int status) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        httpServer.createContext("/", exchange -> {
            try {
                record(exchange);
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private void record(HttpExchange exchange) throws IOException {
        received.add(new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private record Request(String method, String path, String authorization, String body) {
    }
}
