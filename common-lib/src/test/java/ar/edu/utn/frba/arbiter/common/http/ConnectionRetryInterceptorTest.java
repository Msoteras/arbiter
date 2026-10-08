package ar.edu.utn.frba.arbiter.common.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConnectionRetryInterceptorTest {

    private static final String URL = "http://cases-service:8083/api/v1/cases/1";
    private static final int MAX_RETRIES = 3;

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new ConnectionRetryInterceptor(
                        MAX_RETRIES, Duration.ofMillis(1), Duration.ofMillis(2)));
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void refusedConnection_isRetriedUntilTheServiceAnswers() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(URL)).andRespond(withSuccess("ok", null));

        String body = client.get().uri(URL).retrieve().body(String.class);

        assertThat(body).isEqualTo("ok");
        server.verify();
    }

    @Test
    void unresolvableHost_isRetried() {
        server.expect(requestTo(URL)).andRespond(withException(new UnknownHostException("cases-service")));
        server.expect(requestTo(URL)).andRespond(withSuccess());

        client.get().uri(URL).retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void connectTimeout_isRetried_becauseTheRequestNeverLeft() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Connect timed out")));
        server.expect(requestTo(URL)).andRespond(withSuccess());

        client.post().uri(URL).body("payload").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void post_isResentWithTheSameBody() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andExpect(content().string("payload"))
                .andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andExpect(content().string("payload"))
                .andRespond(withSuccess());

        client.post().uri(URL).body("payload").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void stillRefusedAfterEveryRetry_failsWithTheConnectionError() {
        server.expect(ExpectedCount.times(MAX_RETRIES + 1), requestTo(URL))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.get().uri(URL).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(ConnectException.class);
        server.verify();
    }

    @Test
    void errorResponse_isNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.post().uri(URL).body("payload").retrieve().toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void call_retriesAWholeCallThatCouldNotConnect() {
        ConnectionRetryInterceptor retry = new ConnectionRetryInterceptor(MAX_RETRIES, Duration.ofMillis(1), Duration.ofMillis(2));
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.call("cases-service:8083", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void call_doesNotRetryAnErrorResponse() {
        ConnectionRetryInterceptor retry = new ConnectionRetryInterceptor(MAX_RETRIES, Duration.ofMillis(1), Duration.ofMillis(2));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.call("cases-service:8083", () -> {
            attempts.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null);
        })).isInstanceOf(HttpServerErrorException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void readTimeout_isNotRetried_becauseTheRequestAlreadyArrived() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.post().uri(URL).body("payload").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        server.verify();
    }
}
