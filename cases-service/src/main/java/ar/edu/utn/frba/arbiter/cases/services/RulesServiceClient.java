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

    /**
     * The document schedule the referente configured for that branch + claim cause, by NAME — the
     * same read the wizard does, so both gates answer off one source instead of two lists that
     * drift. Every row saved from the panel is persisted mandatory
     * ({@code DocumentRequirementService.upsert}), so the whole list is required.
     *
     * <p>Returns {@code null} when the schedule couldn't be read at all, which is NOT the same as
     * an empty list: empty is an answer ("this claim cause needs no documents"), null is the
     * absence of one. Same distinction rules-service makes internally
     * ({@code InternalDocumentRequirementService.getByCoverage}) and the wizard makes on screen.
     * The caller lets a denuncia through on null rather than leaving the insured out because a
     * service of ours is down, and marks it so {@code DocumentRecheckScheduler} checks it later.
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
     * The short list of documents the expedited path requires for a coverage — the FIRST ROUND the
     * insured is asked for when filing. Read off the same {@code /internal/fast-track} row the
     * engine uses to resolve Fast Track, so the wizard asks for exactly what the gate will look at.
     *
     * <p>Empty (not null) when the insurer configured no list: that is an answer, and the caller
     * falls back to the full schedule — with no list there would be nothing to ask for. {@code null}
     * is the absence of an answer (rules-service didn't respond), and the caller files the denuncia
     * marked as unverified, same contract as {@link #requiredDocumentTypes(String, String)}.
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
     * Si esta aseguradora deriva a peritaje los siniestros del ramo, y desde qué monto. El umbral
     * es una regla de negocio y por eso vive en el motor (decisión #12), no como constante acá ni
     * como una columna que cases-service pudiera leer por atrás.
     *
     * <p>Un motor caído NO es lo mismo que una aseguradora que no deriva: leer la falla como
     * "deshabilitado" le mostraría al analista una política que el referente nunca configuró. Por
     * eso falla explícito en vez de devolver {@link ExpertDerivationPolicy#disabled()}.
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

        /** Un monto reclamado nulo nunca supera el umbral: no hay contra qué compararlo. */
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
