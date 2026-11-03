package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
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
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.time.Duration;

/**
 * Reports each classification step to cases-service, which stores it and pushes it to the engine
 * board. Tighter than {@link CasesServiceNotifier} on purpose: steps go out one at a time from a
 * single queue, and a long wait for a booting cases-service would hold back every step behind it.
 * A lost step only makes the board coarser; the classification never depends on it.
 */
@Component
public class CasesServiceStepPublisher {

    private static final Logger log = LoggerFactory.getLogger(CasesServiceStepPublisher.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;

    @Autowired
    public CasesServiceStepPublisher(
            @Value("${arbiter.cases-service.url:http://localhost:8083}") String casesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret) {
        this(casesServiceUrl, jwtSecret, new ConnectionRetryInterceptor(1, Duration.ofMillis(500), Duration.ofMillis(500)));
    }

    CasesServiceStepPublisher(String casesServiceUrl, String jwtSecret, ConnectionRetryInterceptor connectionRetry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(casesServiceUrl)
                .requestFactory(factory)
                .requestInterceptor(connectionRetry)
                .build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    public void publish(Long caseId, ClassificationStep step) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "classification-service", TenantContext.get());
            restClient.post()
                    .uri("/api/v1/cases/{caseId}/classification-steps", caseId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(step)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("[StepPublisher] Step {} of case {} not delivered: {}", step.stage(), caseId, e.getMessage());
        }
    }
}
