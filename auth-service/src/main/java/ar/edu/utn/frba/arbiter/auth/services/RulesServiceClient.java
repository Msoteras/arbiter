package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.dto.OnboardingTouch;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;

/**
 * Reports saves of the checklist steps auth-service owns (analysts, insured accounts) to
 * rules-service. Best-effort: the checklist is a guide, so a failure is logged and never fails the
 * save.
 */
@Component
public class RulesServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RulesServiceClient.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;

    public RulesServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.rules-service.url}") String rulesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret
    ) {
        this.restClient = restClientBuilder.baseUrl(rulesServiceUrl).build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    /**
     * Inside a transaction, waits for its commit: a save that rolls back didn't happen. The tenant
     * is read now, on the request thread.
     */
    public void recordOnboardingSave(OnboardingStep step) {
        String tenant = TenantContext.get();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(step, tenant);
                }
            });
        } else {
            send(step, tenant);
        }
    }

    private void send(OnboardingStep step, String tenant) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "auth-service-onboarding", tenant);
            restClient.post()
                    .uri("/api/v1/rules/internal/onboarding/touches")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .body(new OnboardingTouch(step, null))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not record a save of onboarding step {}: {}", step, e.getMessage());
        }
    }
}
