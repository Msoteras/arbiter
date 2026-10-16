package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.GeminiProperties;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local server: the timeout lives in the SDK's HTTP client, so a mock can't exercise it. */
class GeminiClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private static final String ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"ok\\":true}"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""";

    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void callThatNeverAnswers_isCutByTheTimeoutAndRetriedOnce_thenFailsAsInfrastructure() {
        GeminiClient client = clientAgainst(call -> false, 200);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chat("prompt", List.of(), null, false))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(calls).hasValue(2);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void callThatHangsOnce_isAnsweredOnTheRetry() {
        GeminiClient client = clientAgainst(call -> call > 1, 200);

        assertThat(client.chat("prompt", List.of(), null, false)).isEqualTo("{\"ok\":true}");
        assertThat(calls).hasValue(2);
    }

    @Test
    void serverError_failsAsInfrastructureWithoutTheLocalRetry() {
        GeminiClient client = clientAgainst(call -> true, 503);

        assertThatThrownBy(() -> client.chat("prompt", List.of(), null, false))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(calls).hasValue(1);
    }

    /** {@code answers} says, by call number, whether the server answers or hangs. */
    private GeminiClient clientAgainst(IntPredicate answers, int status) {
        server = startServer(answers, status);
        Client genAi = Client.builder()
                .apiKey("test-key")
                .httpOptions(HttpOptions.builder()
                        .baseUrl("http://localhost:" + server.getAddress().getPort())
                        .timeout((int) TIMEOUT.toMillis())
                        // No SDK retries on 5xx: even on its last attempt it sleeps before returning,
                        // and that sleep alone outlasts this test's timeout.
                        .retryOptions(HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build())
                        .build())
                .build();
        return new GeminiClient(genAi, new GeminiProperties("p", "global", "gemini-test", 256, 1000, TIMEOUT));
    }

    private HttpServer startServer(IntPredicate answers, int status) {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            // A hung exchange must not block the retry behind it.
            httpServer.setExecutor(executor);
            httpServer.createContext("/", exchange -> handle(exchange, answers.test(calls.incrementAndGet()), status));
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange, boolean answer, int status) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            if (!answer) {
                release.await(10, TimeUnit.SECONDS);
                return;
            }
            byte[] body = ANSWER.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
