package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.SecretKey;

/**
 * System-to-system read of the resolution target from rules-service.
 *
 * <p>The one thing this module does not read straight from the database: {@code insurer_rule} is
 * configuration another module administers, and reading it by SQL would couple this module to a JSONB
 * format it doesn't own. Signs its own service token instead of forwarding the user's, because an
 * analyst may be viewing the dashboard and the referent-only endpoint would reject them.
 */
@Component
public class RulesServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RulesServiceClient.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;

    public RulesServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.rules-service.url:http://rules-service:8081}") String rulesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret
    ) {
        this.restClient = restClientBuilder.baseUrl(rulesServiceUrl).build();
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    /**
     * <b>A rules-service outage doesn't take the dashboard down.</b> Without the target the card shows
     * the average alone, same as for an insurer that never configured one.
     */
    public ResolutionTarget resolutionTarget() {
        try {
            String serviceToken =
                    JwtSupport.issueServiceToken(jwtKey, "reports-service-metrics", TenantContext.get());
            Response response = restClient.get()
                    .uri("/api/v1/rules/internal/resolution-target")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(Response.class);
            if (response == null || !response.enabled() || response.targetDays() == null) {
                return ResolutionTarget.UNSET;
            }
            // The exceeded count is filled in by the caller: rules-service knows nothing about cases.
            return new ResolutionTarget(true, response.targetDays(), 0);
        } catch (RestClientException unavailable) {
            log.warn("[Reports] could not read the resolution target, the dashboard goes without it: {}",
                    unavailable.getMessage());
            return ResolutionTarget.UNSET;
        }
    }

    /**
     * Mirrors rules-service's {@code ResolutionTargetDto}. Not deserialized into {@link ResolutionTarget}
     * because Jackson fails at runtime on its {@code exceeded} component, which the JSON never carries.
     */
    private record Response(boolean enabled, Integer targetDays) {
    }
}
