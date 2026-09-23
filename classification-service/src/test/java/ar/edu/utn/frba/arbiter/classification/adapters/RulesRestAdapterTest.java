package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When rules-service doesn't respond the exception must propagate (for {@code @Retryable}) instead of
 * silently falling back to the baseline; scoring is the one exception. Uses a real JDK HTTP server so
 * the exceptions are the ones the real HTTP layer throws, which is what {@code @Retryable} matches on.
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

    @Test
    void noCoverageId_returnsBaselineWithoutCallingRulesService() {
        RulesRestAdapter adapter = adapterPointingAt("http://localhost:1");

        BusinessRules rules = adapter.getRules("Celulares", null, "Robo en vía pública");

        assertThat(rules.branchId()).isEqualTo("Celulares");
    }

    @Test
    void rulesServiceUp_withNothingConfigured_fallsBackToBaselinePerField() throws IOException {
        server = startServer(exchange -> respondEmpty(exchange));
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        BusinessRules rules = adapter.getRules("Celulares", 1L, "Robo en vía pública");

        BusinessRules baseline = new BaselineRulesAdapter().getRules("Celulares", 1L, "Robo en vía pública");
        assertThat(rules.fastTrackThresholds()).isEqualTo(baseline.fastTrackThresholds());
        assertThat(rules.reportDeadlineHours()).isEqualTo(baseline.reportDeadlineHours());
    }

    @Test
    void rulesServiceUnreachable_propagatesInsteadOfFallingBackToMock() {
        RulesRestAdapter adapter = adapterPointingAt("http://localhost:" + closedPort());

        assertThatThrownBy(() -> adapter.getRules("Celulares", 1L, "Robo en vía pública"))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void rulesServiceReturns500_propagatesInsteadOfFallingBackToMock() throws IOException {
        server = startServer(exchange -> respondError(exchange, 500));
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        assertThatThrownBy(() -> adapter.getRules("Celulares", 1L, "Robo en vía pública"))
                .isInstanceOf(RestClientResponseException.class);
    }

    /** Scoring is the exception: it never gates the decision, so its failure is tolerated. */
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

        BusinessRules baseline = new BaselineRulesAdapter().getRules("Celulares", 1L, "Robo en vía pública");
        assertThat(rules.scoringConfig()).isEqualTo(baseline.scoringConfig());
    }

    /** rules-service answers 400 without claimCause, failing every classification. */
    @Test
    void everyCallCarriesTheQueryParamsRulesServiceRequires() throws IOException {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        server = startServer(exchange -> {
            requested.add(exchange.getRequestURI().toString());
            respondEmpty(exchange);
        });
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        adapter.getRules("Celulares", 1L, "Robo en vía pública");

        assertThat(requested).filteredOn(uri -> uri.contains("document-requirements"))
                .isNotEmpty()
                .allSatisfy(uri -> {
                    assertThat(uri).contains("coverageId=1");
                    // By decoded value, not prefix: a prefix match would pass a truncated value.
                    assertThat(queryParam(uri, "claimCause")).isEqualTo("Robo en vía pública");
                });
        // Insurer-wide endpoints (scoring, fraud record) take no coverage.
        List<String> insurerWide = List.of("/internal/scoring", "/internal/fraud-record-rule");
        assertThat(requested).filteredOn(uri -> uri.contains("/internal/")
                        && insurerWide.stream().noneMatch(uri::contains))
                .isNotEmpty()
                .allSatisfy(uri -> assertThat(uri).contains("coverageId=1"));
    }

    /**
     * The claim cause travels as a name, so accents and spaces must survive: a mangled name finds no
     * agenda and silently falls back to the baseline's documents. Asserted decoded on the server side.
     */
    @Test
    void claimCauseWithAccentsAndSpaces_arrivesIntactAtRulesService() throws IOException {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        server = startServer(exchange -> {
            String uri = exchange.getRequestURI().toString();
            if (uri.contains("document-requirements")) {
                received.add(queryParam(uri, "claimCause"));
            }
            respondEmpty(exchange);
        });
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        adapter.getRules("Celulares", 1L, "Caída");

        assertThat(received).containsExactly("Caída");
    }

    /** An empty agenda is "no documents required", not "not configured". */
    @Test
    void emptyAgenda_isHonoured_notReadAsUnconfigured() throws IOException {
        server = startServer(this::respondEmpty);
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        BusinessRules rules = adapter.getRules("Celulares", 1L, "Caída");

        assertThat(rules.requiredDocumentTypes()).isEmpty();
    }

    @Test
    void noAgendaAnswer_fallsBackToBaseline() throws IOException {
        server = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().contains("document-requirements")) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                respondEmpty(exchange);
            }
        });
        RulesRestAdapter adapter = adapterPointingAt(baseUrl());

        BusinessRules rules = adapter.getRules("Celulares", 1L, "Caída");

        BusinessRules baseline = new BaselineRulesAdapter().getRules("Celulares", 1L, "Caída");
        assertThat(rules.requiredDocumentTypes()).isEqualTo(baseline.requiredDocumentTypes());
    }

    // ── Infra ────────────────────────────────────────────────────────────────

    private RulesRestAdapter adapterPointingAt(String url) {
        return new RulesRestAdapter(url, JWT_SECRET, new BaselineRulesAdapter());
    }

    private String queryParam(String uri, String name) {
        int start = uri.indexOf('?');
        if (start < 0) {
            return null;
        }
        for (String pair : uri.substring(start + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
        // Closed on exit: nobody listens on the port, so connecting is refused.
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

    /** 200 with an empty body: what rules-service returns when nothing is configured. */
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
