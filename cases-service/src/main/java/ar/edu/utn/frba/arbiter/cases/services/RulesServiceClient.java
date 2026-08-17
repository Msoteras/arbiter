package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Reads the insurer's derivation policy off rules-service. The threshold is a business rule, and
 * business rules live in the engine's DB where the referente edits them (decisión #12) — not as a
 * constant here, and not as a column cases-service could read behind the engine's back.
 */
@Component
public class RulesServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RulesServiceClient.class);

    private final RestClient restClient;
    private final HttpServletRequest currentRequest;

    public RulesServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.rules-service.url:http://rules-service:8081}") String rulesServiceUrl,
            HttpServletRequest currentRequest
    ) {
        this.restClient = restClientBuilder.baseUrl(rulesServiceUrl).build();
        this.currentRequest = currentRequest;
    }

    /**
     * Deriving always runs inside the analyst's own tenant, so the caller's JWT is forwarded as-is
     * — no service token needed, and the identity chain stays intact.
     *
     * <p>An unreachable engine is <b>not</b> the same as an insurer that doesn't derive: reading a
     * failure as "disabled" would show the analyst a policy the referente never set. It fails
     * instead, and the screen says so.
     */
    public ExpertDerivationPolicy expertDerivationPolicy(Long branchId) {
        try {
            ExpertDerivationPolicy policy = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/rules/internal/expert-derivation")
                            .queryParam("branchId", branchId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, currentRequest.getHeader(HttpHeaders.AUTHORIZATION))
                    .retrieve()
                    .body(ExpertDerivationPolicy.class);
            return policy != null ? policy : ExpertDerivationPolicy.disabled();
        } catch (Exception e) {
            log.error("Could not read the expert derivation policy for branch {}", branchId, e);
            throw new RulesUnavailableException(e);
        }
    }

    /**
     * Mirrors rules-service's {@code ExpertDerivationDto}. A copy and not a shared type, same as
     * classification-service does with the other internal reads: the engine owns its DTO and each
     * consumer maps it into what it needs.
     */
    public record ExpertDerivationPolicy(boolean enabled, BigDecimal minClaimedAmount, Long ruleId) {

        static ExpertDerivationPolicy disabled() {
            return new ExpertDerivationPolicy(false, null, null);
        }

        /** Null claimed amount never clears the bar: the case has no amount to compare. */
        public boolean allows(BigDecimal claimedAmount) {
            return enabled
                    && claimedAmount != null
                    && minClaimedAmount != null
                    && claimedAmount.compareTo(minClaimedAmount) >= 0;
        }
    }
}
