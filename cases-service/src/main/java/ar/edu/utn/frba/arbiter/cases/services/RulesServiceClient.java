package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * System-to-system read of rules-service config that cases-service needs before or instead of
 * classification — the intake gate's insurer-scoped hard rules ({@link PolicyEligibilityValidator})
 * and, for the wizard, which claim causes a coverage excludes. Same criterion
 * {@code ClassificationServiceClient} uses for its own internal calls. Always signs a fresh service
 * token instead of forwarding the caller's JWT: {@code createCase} may already have switched
 * {@link TenantContext} to the policy's issuing tenant (a different insurer than the one in the
 * caller's login token), and rules-service resolves its own tenant off the token it receives.
 */
@Component
public class RulesServiceClient {

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
     * The insurer's current POLICY_STANDING configuration: whether arrears is being evaluated at
     * all, and — if it is — whether it rejects the denuncia at intake or lets it through to
     * standby in the engine. No configuration ⇒ {@code enabled=false}, same as "no row" means
     * "not evaluated" everywhere else in the hard rules.
     */
    public PolicyStandingRule policyStandingRule() {
        String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-intake", TenantContext.get());
        return restClient.get()
                .uri("/api/v1/rules/internal/policy-standing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .retrieve()
                .body(PolicyStandingRule.class);
    }

    /** Mirrors rules-service's InsurerHardRuleDto for the POLICY_STANDING row. */
    public record PolicyStandingRule(boolean enabled, String onArrears) {

        public boolean rejectsAtIntake() {
            return enabled && "REJECT".equals(onArrears);
        }
    }

    /**
     * Hechos generadores (claim_cause ids) the given coverage does NOT cover — a blacklist, not a
     * whitelist: a coverage covers every claim cause of its branch except the ones listed here.
     * Empty (not null) when the coverage has no {@code COVERAGE_EXCLUSION} row, active or not — same
     * "no row ⇒ nothing excluded" the engine already applies.
     */
    public List<Long> excludedClaimCauseIds(Long coverageId) {
        String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-claim-causes", TenantContext.get());
        EvaluableRulesResponse resp = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/internal/evaluable").queryParam("coverageId", coverageId).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .retrieve()
                .body(EvaluableRulesResponse.class);
        if (resp == null || resp.rules() == null) {
            return List.of();
        }
        return resp.rules().stream()
                .filter(r -> "COVERAGE_EXCLUSION".equals(r.ruleType()) && r.excludedClaimCauseIds() != null)
                .flatMap(r -> r.excludedClaimCauseIds().stream())
                .toList();
    }

    /** Mirrors rules-service's EvaluableRulesDto/EvaluableRuleDto (only the fields this client reads). */
    private record EvaluableRulesResponse(List<EvaluableRuleJson> rules) {
    }

    private record EvaluableRuleJson(String ruleType, List<Long> excludedClaimCauseIds) {
    }
}
