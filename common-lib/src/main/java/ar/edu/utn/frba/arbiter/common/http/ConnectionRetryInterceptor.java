package ar.edu.utn.frba.arbiter.common.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * On Railway's private network a sleeping service refuses connections until it has booted. Only
 * failures where the request never got through (refused, unresolvable or connect timeout) are
 * retried: repeating one that arrived could run a POST twice.
 */
public class ConnectionRetryInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRetryInterceptor.class);

    private final RetryTemplate retryTemplate;

    public ConnectionRetryInterceptor(int maxRetries, Duration initialDelay, Duration maxDelay) {
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(initialDelay)
                .multiplier(2)
                .maxDelay(maxDelay)
                .predicate(ConnectionRetryInterceptor::neverReachedTheServer)
                .build());
        this.retryTemplate.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.warn("{} unreachable ({}); retry {} of {}", retryable.getName(),
                        state.getLastException(), state.getRetryCount(), maxRetries);
            }
        });
    }

    /** A Spring Boot module boots in 5-7 s on Railway; this waits ~23 s in total. */
    public static ConnectionRetryInterceptor forBootingModule() {
        return new ConnectionRetryInterceptor(5, Duration.ofSeconds(1), Duration.ofSeconds(8));
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try {
            return retryTemplate.execute(new Retryable<>() {
                @Override
                public ClientHttpResponse execute() throws IOException {
                    return execution.execute(request, body);
                }

                // Host and port only: paths can carry personal data (e.g. the insured's DNI).
                @Override
                public String getName() {
                    return request.getMethod() + " " + request.getURI().getAuthority();
                }
            });
        } catch (RetryException e) {
            Throwable last = e.getCause();
            if (last instanceof IOException io) {
                throw io;
            }
            if (last instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(last);
        }
    }

    /**
     * The same retry around a whole call, for a body too large to buffer: as an interceptor the
     * request is held in memory once more so it can be resent.
     *
     * @param target host and port only, for the log
     */
    public <T> T call(String target, Supplier<T> action) {
        try {
            return retryTemplate.execute(new Retryable<>() {
                @Override
                public T execute() {
                    return action.get();
                }

                @Override
                public String getName() {
                    return target;
                }
            });
        } catch (RetryException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static boolean neverReachedTheServer(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException
                    || isConnectTimeout(cause)) {
                return true;
            }
        }
        return false;
    }

    /** HttpURLConnection tells a connect timeout from a read timeout only by the message. */
    private static boolean isConnectTimeout(Throwable cause) {
        return cause instanceof HttpConnectTimeoutException
                || (cause instanceof SocketTimeoutException && "Connect timed out".equalsIgnoreCase(cause.getMessage()));
    }
}
