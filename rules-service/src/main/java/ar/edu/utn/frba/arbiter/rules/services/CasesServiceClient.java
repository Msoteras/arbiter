package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.rules.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.rules.exceptions.OnboardingUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;

/** System-to-system read of what cases-service knows about the insurer's setup. */
@Component
public class CasesServiceClient {

    private final RestClient restClient;
    private final SecretKey jwtKey;

    public CasesServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.cases-service.url}") String casesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret
    ) {
        this.restClient = restClientBuilder.baseUrl(casesServiceUrl).build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    public OnboardingCaseCounts onboardingCounts() {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "rules-service-onboarding", TenantContext.get());
            OnboardingCaseCounts counts = restClient.get()
                    .uri("/api/v1/onboarding/internal/counts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(OnboardingCaseCounts.class);
            if (counts == null) {
                throw new IllegalStateException("Empty onboarding counts from cases-service");
            }
            return counts;
        } catch (Exception e) {
            throw new OnboardingUnavailableException(e);
        }
    }
}
