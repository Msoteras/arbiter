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
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.time.Duration;

@Component
public class CasesServiceNotifier {

    private static final Logger log = LoggerFactory.getLogger(CasesServiceNotifier.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;

    @Autowired
    public CasesServiceNotifier(
            @Value("${arbiter.cases-service.url:http://localhost:8083}") String casesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret) {
        this(casesServiceUrl, jwtSecret, ConnectionRetryInterceptor.forBootingModule());
    }

    CasesServiceNotifier(String casesServiceUrl, String jwtSecret, ConnectionRetryInterceptor connectionRetry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(casesServiceUrl)
                .requestFactory(factory)
                .requestInterceptor(connectionRetry)
                .build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    public void classificationFinished(Long caseId, ClassificationFinished.Outcome outcome) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "classification-service", TenantContext.get());
            restClient.post()
                    .uri("/api/v1/cases/{caseId}/classification-finished", caseId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClassificationFinished(outcome))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[CasesNotifier] cases-service notified that case {} finished: {}", caseId, outcome);
        } catch (Exception e) {
            log.warn("[CasesNotifier] Could not notify cases-service that case {} finished ({}); "
                    + "its polling will pick it up: {}", caseId, outcome, e.getMessage());
        }
    }
}
