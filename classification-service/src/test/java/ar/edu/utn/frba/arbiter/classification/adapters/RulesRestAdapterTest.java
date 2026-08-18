package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.adapters.mock.MockRulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RulesRestAdapter}'s availability contract: the configuration the referente entered
 * (Fast Track, hard rules, agenda, texts) can't be silently substituted by the baseline when
 * rules-service doesn't respond — the exception has to propagate so
 * {@code ClaimClassificationService}'s @Retryable and cases-service's poller do what they already
 * do with any classification that doesn't close (retry, and if not, CLASSIFICATION_FAILED).
 * Scoring is the one documented exception (parallel signal, gates nothing).
 *
 * <p>A real server (JDK's {@code com.sun.net.httpserver.HttpServer}) instead of RestClient mocks,
 * to exercise the exceptions exactly as the real HTTP layer throws them
 * ({@code ResourceAccessException} on connection refused, {@code RestClientResponseException} on
 * 5xx) — which is exactly what {@code @Retryable} matches on.
 */
class RulesRestAdapterTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** No coverage, rules-service isn't even contacted: the baseline alone. */
    @Test
    void noCoverageId_returnsBaselineWithoutCallingRulesService() {
        RulesRestAdapter adapter = adapterPointingAt("http://localhost:1");

        BusinessRules rules = adapter.getRules("Celulares", null, "Robo en vía pública");

        assertThat(rules.branchId()).isEqualTo("Celulares");
    }

    /** rules-service up but "nothing configured" (200 with empty bodies): baseline untouched. */
    @Test
    void rulesServiceUp_withNothingConfigured_fallsBackToBaselinePerField() throws IOException {
        server = startServer(exchange -> respondEmpty(exchange));
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        BusinessRules rules = adapter.getRules("Celulares", 1L, "Robo en vía pública");

        BusinessRules baseline = new MockRulesAdapter().getRules("Celulares", 1L, "Robo en vía pública");
        assertThat(rules.fastTrackThresholds()).isEqualTo(baseline.fastTrackThresholds());
        assertThat(rules.reportDeadlineHours()).isEqualTo(baseline.reportDeadlineHours());
    }

    /** With rules-service down (connection refused), reading the rules blows up — no fallback. */
    @Test
    void rulesServiceUnreachable_propagatesInsteadOfFallingBackToMock() {
        // Closed port on purpose: nobody's listening, connection refused guaranteed.
        RulesRestAdapter adapter = adapterPointingAt("http://localhost:" + closedPort());

        assertThatThrownBy(() -> adapter.getRules("Celulares", 1L, "Robo en vía pública"))
                .isInstanceOf(ResourceAccessException.class);
    }

    /** A 500 from rules-service doesn't get swallowed either: the other type @Retryable retries on. */
    @Test
    void rulesServiceReturns500_propagatesInsteadOfFallingBackToMock() throws IOException {
        server = startServer(exchange -> respondError(exchange, 500));
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        assertThatThrownBy(() -> adapter.getRules("Celulares", 1L, "Robo en vía pública"))
                .isInstanceOf(RestClientResponseException.class);
    }

    /**
     * Scoring is the documented exception: if ITS endpoint specifically fails but the rest of
     * rules-service answers, classification proceeds — the score is a parallel signal that never
     * gates the decision.
     */
    @Test
    void scoringAlone_stillFallsBackToBaseline_whenEverythingElseSucceeds() throws IOException {
        server = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/internal/scoring")) {
                respondError(exchange, 500);
            } else {
                respondEmpty(exchange);
            }
        });
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        BusinessRules rules = adapter.getRules("Celulares", 1L, "Robo en vía pública");

        BusinessRules baseline = new MockRulesAdapter().getRules("Celulares", 1L, "Robo en vía pública");
        assertThat(rules.scoringConfig()).isEqualTo(baseline.scoringConfig());
    }

    // ── Infra ────────────────────────────────────────────────────────────────

    private RulesRestAdapter adapterPointingAt(String url) {
        return new RulesRestAdapter(url, JWT_SECRET, new MockRulesAdapter());
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
        // The socket closes when the try-with-resources exits: the port is free but nobody's
        // listening, so connecting gives connection refused — exactly "the service is down".
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private HttpServer startServer(Handler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    /** 200 with an empty body: the real response for "this insurer configured nothing here". */
    private void respondEmpty(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestURI().getPath().contains("document-requirements")
                ? "[]".getBytes(StandardCharsets.UTF_8)
                : "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private void respondError(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
