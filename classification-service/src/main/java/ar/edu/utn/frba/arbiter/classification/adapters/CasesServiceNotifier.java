package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished;
import ar.edu.utn.frba.arbiter.common.http.ConnectionRetryInterceptor;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.time.Duration;

/**
 * Tells cases-service that a run ended. A lost notice leaves the case pending until the polling
 * sweep finds it, so a failed one is sent again. Sending it twice is harmless: cases-service only
 * moves a case that is still pending.
 */
@Component
public class CasesServiceNotifier {

    private static final Logger log = LoggerFactory.getLogger(CasesServiceNotifier.class);

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final SecretKey jwtKey;
    /** Doubled after each failed attempt. */
    private final Duration firstRetryDelay;

    @Autowired
    public CasesServiceNotifier(
            @Value("${arbiter.cases-service.url:http://localhost:8083}") String casesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret,
            @Value("${arbiter.cases-service.notice-retry-delay-ms:5000}") long firstRetryDelayMs) {
        this(casesServiceUrl, jwtSecret, ConnectionRetryInterceptor.forBootingModule(),
                Duration.ofMillis(firstRetryDelayMs), Duration.ofSeconds(30));
    }

    /**
     * @param readTimeout generous on purpose: on a COMPLETED notice cases-service reads the result
     *                    back from this module before answering, and a sleeping module on Railway
     *                    took longer than the old 10 s.
     */
    CasesServiceNotifier(String casesServiceUrl, String jwtSecret, ConnectionRetryInterceptor connectionRetry,
                         Duration firstRetryDelay, Duration readTimeout) {
        this.firstRetryDelay = firstRetryDelay;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(casesServiceUrl)
                .requestFactory(factory)
                .requestInterceptor(connectionRetry)
                .build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    public void classificationFinished(Long caseId, ClassificationFinished.Outcome outcome) {
        Duration delay = firstRetryDelay;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                send(caseId, outcome);
                log.info("[CasesNotifier] cases-service notified that case {} finished: {}", caseId, outcome);
                return;
            } catch (HttpClientErrorException e) {
                // A 4xx answers the same way every time: sending it again would change nothing.
                log.warn("[CasesNotifier] cases-service rejected the notice that case {} finished ({}); "
                        + "its polling will pick it up: {}", caseId, outcome, e.getMessage());
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("[CasesNotifier] Could not notify cases-service that case {} finished ({}) after {} "
                            + "attempts; its polling will pick it up: {}", caseId, outcome, MAX_ATTEMPTS, e.getMessage());
                    return;
                }
                log.info("[CasesNotifier] Notice for case {} failed (attempt {}/{}), retrying in {} ms: {}",
                        caseId, attempt, MAX_ATTEMPTS, delay.toMillis(), e.getMessage());
                if (!pause(delay)) {
                    return;
                }
                delay = delay.multipliedBy(2);
            }
        }
    }

    private void send(Long caseId, ClassificationFinished.Outcome outcome) {
        String serviceToken = JwtSupport.issueServiceToken(jwtKey, "classification-service", TenantContext.get());
        restClient.post()
                .uri("/api/v1/cases/{caseId}/classification-finished", caseId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ClassificationFinished(outcome))
                .retrieve()
                .toBodilessEntity();
    }

    /** @return false if interrupted: the thread is shutting down and the polling covers the case. */
    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
