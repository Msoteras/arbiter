package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Overlays what the referente configured in rules-service on {@link BaselineRulesAdapter}'s baseline.
 * An empty 200 means "not configured" and keeps the baseline. Runs async, so it authenticates with a
 * service token carrying the tenant schema from {@link TenantContext}.
 *
 * <p>An unreachable rules-service is <b>not</b> best-effort: silently falling back to the baseline
 * could Fast Track a claim with rules nobody at the insurer configured. The exception propagates to
 * {@code @Retryable} and, if it persists, the case ends up {@code CLASSIFICATION_FAILED}. Only
 * {@link #overlayScoring} is best-effort, since the fraud score never gates a decision.
 *
 * <p>Excluded in the {@code test} profile so integration tests run against the baseline only.
 */
@Component
@Primary
@Profile("!test")
public class RulesRestAdapter implements RulesAdapter {

    private static final Logger log = LoggerFactory.getLogger(RulesRestAdapter.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;
    private final BaselineRulesAdapter defaults;

    public RulesRestAdapter(
            @Value("${arbiter.rules-service.url:http://localhost:8081}") String rulesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret,
            BaselineRulesAdapter defaults) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().baseUrl(rulesServiceUrl).requestFactory(factory).build();
        this.jwtKey = JwtSupport.key(jwtSecret);
        this.defaults = defaults;
    }

    @Override
    public BusinessRules getRules(String branchId, Long coverageId, String claimCauseId) {
        BusinessRules base = defaults.getRules(branchId, coverageId, claimCauseId);
        if (coverageId == null) {
            return base;
        }
        return overlayScoring(
                overlayFraudRecordPolicy(
                        overlayCoverageLimits(
                                overlayEvaluableRules(
                                        overlayDocumentRequirements(
                                                overlayRuleTexts(overlayFastTrack(base, coverageId), coverageId),
                                                coverageId, claimCauseId),
                                        coverageId),
                                coverageId)));
    }

    /** Insurer-wide like scoring, but not best-effort: it can veto Fast Track. */
    private BusinessRules overlayFraudRecordPolicy(BusinessRules rules) {
        return rules.toBuilder().fraudRecordPolicy(getFraudRecordPolicy()).build();
    }

    @Override
    public BusinessRules.FraudRecordPolicy getFraudRecordPolicy() {
        FraudRecordRuleResponse rule = restClient.get()
                .uri("/api/v1/rules/internal/fraud-record-rule")
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(FraudRecordRuleResponse.class);
        if (rule == null || rule.ruleId() == null) {
            log.debug("[RulesRestAdapter] No fraud record rule configured — default window, no veto");
            return BusinessRules.FraudRecordPolicy.unconfigured();
        }
        int windowMonths = rule.windowMonths() == null
                ? BusinessRules.FraudRecordPolicy.DEFAULT_WINDOW_MONTHS
                : rule.windowMonths();
        log.info("[RulesRestAdapter] Fraud record rule loaded — windowMonths={} blocksFastTrack={}",
                windowMonths, rule.vetoesFastTrack());
        return BusinessRules.FraudRecordPolicy.builder()
                .ruleId(rule.ruleId())
                .windowMonths(windowMonths)
                .blocksFastTrack(rule.vetoesFastTrack())
                .build();
    }

    /** One config per insurer; replaces the baseline only when enabled and usable. */
    private BusinessRules overlayScoring(BusinessRules rules) {
        try {
            ScoringResponse scoring = restClient.get()
                    .uri("/api/v1/rules/internal/scoring")
                    .header(HttpHeaders.AUTHORIZATION, serviceToken())
                    .retrieve()
                    .body(ScoringResponse.class);
            if (scoring == null || !scoring.enabled()
                    || scoring.factors() == null || scoring.factors().isEmpty()
                    || scoring.bands() == null || scoring.bands().isEmpty()) {
                log.debug("[RulesRestAdapter] No scoring config in DB (or disabled) — using baseline");
                return rules;
            }
            BusinessRules.ScoringConfig mapped = scoring.toScoringConfig();
            if (mapped == null) {
                log.warn("[RulesRestAdapter] Scoring config in DB is unusable (bad band?) — using baseline");
                return rules;
            }
            log.info("[RulesRestAdapter] Scoring config loaded from rules-service — {} factors, {} bands",
                    mapped.factors().size(), mapped.bands().size());
            return rules.toBuilder().scoringConfig(mapped).build();
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for scoring — baseline: {}", e.getMessage());
            return rules;
        }
    }

    private BusinessRules overlayCoverageLimits(BusinessRules rules, Long coverageId) {
        CoverageLimitsResponse limits = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/internal/coverage-limits")
                        .queryParam("coverageId", coverageId).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(CoverageLimitsResponse.class);
        if (limits == null || limits.isEmpty()) {
            log.debug("[RulesRestAdapter] No coverage limits in DB for coverage {} — using baseline", coverageId);
            return rules;
        }
        log.info("[RulesRestAdapter] Coverage limits loaded for coverage {} — deadlineHours={} "
                        + "maxEventsPerYear={} waitingPeriodDays={}",
                coverageId, limits.reportDeadlineHours(), limits.maxEventsPerYear(),
                limits.waitingPeriodDays());
        return rules.toBuilder()
                .reportDeadlineHours(limits.reportDeadlineHours())
                .maxEventsPerYear(limits.maxEventsPerYear())
                .waitingPeriodDays(limits.waitingPeriodDays())
                .coversFamilyGroup(limits.coversFamilyGroup())
                .claimExhaustsCoverage(limits.claimExhaustsCoverage())
                .build();
    }

    private BusinessRules overlayEvaluableRules(BusinessRules rules, Long coverageId) {
        EvaluableRulesResponse resp = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/internal/evaluable")
                        .queryParam("coverageId", coverageId).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(EvaluableRulesResponse.class);
        if (resp == null || resp.isEmpty()) {
            log.debug("[RulesRestAdapter] No evaluable rules in DB for coverage {} — using baseline", coverageId);
            return rules;
        }
        List<BusinessRules.EvaluableRule> mapped = resp.rules().stream()
                .map(r -> BusinessRules.EvaluableRule.builder()
                        .id(r.id())
                        .ruleType(r.ruleType())
                        .effect(r.effect())
                        .blocksFastTrack(r.blocksFastTrack())
                        .excludedClaimCauseIds(r.excludedClaimCauseIds())
                        .deadlineHours(r.deadlineHours())
                        .build())
                .toList();
        log.info("[RulesRestAdapter] Evaluable rules loaded from rules-service for coverage {} — {} rules",
                coverageId, mapped.size());
        return rules.toBuilder().evaluableRules(mapped).build();
    }

    private BusinessRules overlayFastTrack(BusinessRules base, Long coverageId) {
        FastTrackResponse ft = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/internal/fast-track")
                        .queryParam("coverageId", coverageId).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(FastTrackResponse.class);
        if (ft == null || ft.isEmpty()) {
            log.debug("[RulesRestAdapter] No Fast Track config in DB for coverage {} — using baseline", coverageId);
            return base;
        }
        log.info("[RulesRestAdapter] Fast Track thresholds loaded from rules-service for coverage {}", coverageId);
        BusinessRules.BusinessRulesBuilder overlaid = base.toBuilder()
                .fastTrackThresholds(new BusinessRules.FastTrackThresholds(
                        ft.maxClaimedAmountRatio(), ft.maxPriorClaims(),
                        ft.priorClaimsWindowMonths(), ft.minPolicyAgeMonths(),
                        ft.requiresUpToDatePolicy(), ft.requiredDocumentTypes()));

        // Replaced, not merged: the baseline's criteria describe different thresholds and would
        // contradict the referente's in the prompt.
        if (ft.criteria() != null) {
            overlaid.fastTrackCriteria(List.copyOf(ft.criteria()));
        }
        return overlaid.build();
    }

    /** Free-text rules and exclusions for the prompt; replaces the baseline rather than adding to it. */
    private BusinessRules overlayRuleTexts(BusinessRules rules, Long coverageId) {
        RuleTextsResponse texts = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/internal/rule-texts")
                        .queryParam("coverageId", coverageId).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(RuleTextsResponse.class);
        if (texts == null || texts.isEmpty()) {
            log.debug("[RulesRestAdapter] No rule texts in DB for coverage {} — using baseline", coverageId);
            return rules;
        }
        BusinessRules.BusinessRulesBuilder builder = rules.toBuilder();
        if (texts.businessRules() != null && !texts.businessRules().isEmpty()) {
            builder.rules(texts.businessRules());
        }
        if (texts.exclusions() != null && !texts.exclusions().isEmpty()) {
            builder.exclusions(texts.exclusions());
        }
        log.info("[RulesRestAdapter] Rule texts loaded from rules-service for coverage {} — {} rules, {} exclusions",
                coverageId,
                texts.businessRules() == null ? 0 : texts.businessRules().size(),
                texts.exclusions() == null ? 0 : texts.exclusions().size());
        return builder.build();
    }

    /**
     * {@code claimCause} goes as a name (all {@code ClaimReport} carries); rules-service resolves it
     * against the coverage's branch. It is required: omitting it returns 400 and fails the classification.
     */
    private BusinessRules overlayDocumentRequirements(BusinessRules rules, Long coverageId, String claimCause) {
        List<String> agenda = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/document-requirements/internal")
                        .queryParam("coverageId", coverageId)
                        .queryParam("claimCause", claimCause).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
        // Empty is an answer ("no documents required"); only null means "not configured".
        if (agenda == null) {
            log.debug("[RulesRestAdapter] No document agenda in DB for coverage {} — using baseline", coverageId);
            return rules;
        }
        log.info("[RulesRestAdapter] Document agenda loaded from rules-service for coverage {} — {} docs",
                coverageId, agenda.size());
        return rules.toBuilder().requiredDocumentTypes(agenda).build();
    }

    private String serviceToken() {
        return "Bearer " + JwtSupport.issueServiceToken(jwtKey, "classification-service", TenantContext.get());
    }

    /** Mirrors rules-service's RuleTextsDto. */
    private record RuleTextsResponse(List<String> exclusions, List<String> businessRules) {

        boolean isEmpty() {
            return (exclusions == null || exclusions.isEmpty())
                    && (businessRules == null || businessRules.isEmpty());
        }
    }

    /** Mirrors rules-service's EvaluableRulesDto. */
    private record EvaluableRulesResponse(List<EvaluableRuleJson> rules) {

        boolean isEmpty() {
            return rules == null || rules.isEmpty();
        }
    }

    /** Mirrors rules-service's EvaluableRuleDto. */
    private record EvaluableRuleJson(
            Long id,
            String ruleType,
            String effect,
            boolean blocksFastTrack,
            List<Long> excludedClaimCauseIds,
            Long deadlineHours) {}

    /**
     * Mirrors rules-service's FraudRecordRuleDto. Boxed on purpose: "nothing configured" is an empty
     * 200, and a primitive would turn it into a parse error that sinks the classification.
     */
    private record FraudRecordRuleResponse(
            Long ruleId, Integer windowMonths, Boolean blocksFastTrack) {

        boolean vetoesFastTrack() {
            return Boolean.TRUE.equals(blocksFastTrack);
        }
    }

    /** Mirrors rules-service's CoverageLimitsDto. */
    private record CoverageLimitsResponse(
            Long reportDeadlineHours, Integer maxEventsPerYear, Integer waitingPeriodDays,
            Boolean coversFamilyGroup, Boolean claimExhaustsCoverage) {

        boolean isEmpty() {
            return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null
                    && coversFamilyGroup == null && claimExhaustsCoverage == null;
        }
    }

    /** Mirrors rules-service's ScoringConfigDto. */
    private record ScoringResponse(Long id, boolean enabled, boolean fullAnalysisOnFastTrack,
                                   List<ScoringFactorJson> factors, List<ScoringBandJson> bands) {

        /** Maps to classification's ScoringConfig; null if any band isn't a valid RiskBand. */
        BusinessRules.ScoringConfig toScoringConfig() {
            List<BusinessRules.ScoringConfig.FactorWeight> factorWeights = factors.stream()
                    .map(f -> BusinessRules.ScoringConfig.FactorWeight.builder()
                            .factorId(f.factorId())
                            .weight(f.weight() == null ? 0.0 : f.weight())
                            .build())
                    .toList();
            List<BusinessRules.ScoringConfig.Band> scoreBands = new ArrayList<>();
            for (ScoringBandJson b : bands) {
                RiskBand riskBand;
                try {
                    riskBand = RiskBand.valueOf(b.band());
                } catch (IllegalArgumentException | NullPointerException e) {
                    return null;
                }
                scoreBands.add(BusinessRules.ScoringConfig.Band.builder()
                        .band(riskBand)
                        .minScoreInclusive(b.minScoreInclusive() == null ? 0.0 : b.minScoreInclusive())
                        .build());
            }
            return BusinessRules.ScoringConfig.builder()
                    .id(id)
                    .factors(factorWeights)
                    .bands(scoreBands)
                    .fullAnalysisOnFastTrack(fullAnalysisOnFastTrack)
                    .build();
        }
    }

    private record ScoringFactorJson(String factorId, Double weight) {}

    private record ScoringBandJson(String band, Double minScoreInclusive) {}

    /** Mirrors rules-service's FastTrackConfigDto. */
    private record FastTrackResponse(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            Integer priorClaimsWindowMonths,
            Integer minPolicyAgeMonths,
            Boolean requiresUpToDatePolicy,
            List<String> requiredDocumentTypes,
            List<String> criteria) {

        boolean isEmpty() {
            return maxClaimedAmountRatio == null
                    && maxPriorClaims == null
                    && priorClaimsWindowMonths == null
                    && minPolicyAgeMonths == null
                    && requiresUpToDatePolicy == null
                    && (requiredDocumentTypes == null || requiredDocumentTypes.isEmpty())
                    && (criteria == null || criteria.isEmpty());
        }
    }
}
