package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.util.List;

/**
 * System-to-system reads of rules-service config that cases-service needs outside classification.
 * Always signs a fresh service token instead of forwarding the caller's JWT: {@code createCase} may
 * already have switched {@link TenantContext} to the policy's issuing insurer, and rules-service
 * resolves its tenant from the token it receives.
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

    /** No configuration means {@code enabled=false}: arrears isn't evaluated. */
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
     * Claim cause ids the coverage does NOT cover (a blacklist). Empty, not null, when the coverage
     * has no {@code COVERAGE_EXCLUSION} rule.
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

    /**
     * The whole schedule is mandatory: every row saved from the panel is persisted as required.
     *
     * <p>Returns {@code null} when the schedule couldn't be read, which is not the same as an empty
     * list ("no documents needed"). On null the caller files the claim anyway and marks it for
     * {@code DocumentRecheckScheduler}.
     */
    public List<String> requiredDocumentTypes(String branch, String claimCause) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-documents", TenantContext.get());
            return restClient.get()
                    .uri(uri -> uri.path("/api/v1/rules/document-requirements/for-branch")
                            .queryParam("branch", branch)
                            .queryParam("claimCause", claimCause)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Could not read the document schedule for branch '{}' / claim cause '{}'",
                    branch, claimCause, e);
            return null;
        }
    }

    /**
     * The first round of documents asked for at filing, read from the same Fast Track config the
     * engine uses. Empty when none is configured (the caller falls back to the full schedule);
     * {@code null} when rules-service didn't answer, same contract as {@link #requiredDocumentTypes}.
     */
    public List<String> fastTrackDocumentTypes(Long coverageId) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-fast-track", TenantContext.get());
            FastTrackConfigResponse config = restClient.get()
                    .uri(uri -> uri.path("/api/v1/rules/internal/fast-track")
                            .queryParam("coverageId", coverageId).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(FastTrackConfigResponse.class);
            return config == null || config.requiredDocumentTypes() == null
                    ? List.of()
                    : config.requiredDocumentTypes();
        } catch (Exception e) {
            log.error("Could not read the Fast Track document list for coverage {}", coverageId, e);
            return null;
        }
    }

    /** Mirrors the only field this client reads off rules-service's FastTrackConfigDto. */
    private record FastTrackConfigResponse(List<String> requiredDocumentTypes) {
    }

    /**
     * Fails explicitly instead of returning {@link ExpertDerivationPolicy#disabled()}: reading an
     * outage as "disabled" would show the analyst a policy the referent never configured.
     */
    public ExpertDerivationPolicy expertDerivationPolicy(Long branchId) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-peritaje", TenantContext.get());
            ExpertDerivationPolicy policy = restClient.get()
                    .uri(uri -> uri.path("/api/v1/rules/internal/expert-derivation")
                            .queryParam("branchId", branchId).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(ExpertDerivationPolicy.class);
            return policy != null ? policy : ExpertDerivationPolicy.disabled();
        } catch (Exception e) {
            log.error("Could not read the expert derivation policy for branch {}", branchId, e);
            throw new RulesUnavailableException(e);
        }
    }

    /**
     * Which claim causes of a branch the insurer sends to a repair shop. Fails explicitly when
     * rules-service doesn't answer, for the same reason as {@link #expertDerivationPolicy(Long)}.
     */
    public RepairDerivationPolicy repairDerivationPolicy(Long branchId) {
        try {
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-reparacion", TenantContext.get());
            RepairDerivationPolicy policy = restClient.get()
                    .uri(uri -> uri.path("/api/v1/rules/internal/repair-derivation")
                            .queryParam("branchId", branchId).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(RepairDerivationPolicy.class);
            return policy != null ? policy : RepairDerivationPolicy.disabled();
        } catch (Exception e) {
            log.error("Could not read the repair derivation policy for branch {}", branchId, e);
            throw new RulesUnavailableException(e);
        }
    }

    /** Mirrors rules-service's ExpertDerivationDto. */
    public record ExpertDerivationPolicy(boolean enabled, BigDecimal minClaimedAmount, Long ruleId) {

        static ExpertDerivationPolicy disabled() {
            return new ExpertDerivationPolicy(false, null, null);
        }

        public boolean allows(BigDecimal claimedAmount) {
            return enabled
                    && claimedAmount != null
                    && minClaimedAmount != null
                    && claimedAmount.compareTo(minClaimedAmount) >= 0;
        }
    }

    public record RepairDerivationPolicy(boolean enabled, List<Long> claimCauseIds, Long ruleId) {

        static RepairDerivationPolicy disabled() {
            return new RepairDerivationPolicy(false, List.of(), null);
        }

        public boolean allows(Long claimCauseId) {
            return enabled && claimCauseIds != null && claimCauseIds.contains(claimCauseId);
        }
    }
}
