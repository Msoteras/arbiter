package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.adapters.mock.MockRulesAdapter;
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
 * Primary {@link RulesAdapter}: reads what the referente configured in rules-service (the DB) and
 * overlays it on the {@link MockRulesAdapter} baseline — the Fast Track thresholds that gate the
 * expedited path, the free-text rules/exclusions that go into the LLM prompt, the document agenda
 * that the missing-docs gate checks, and the hard evaluable rules (exclusions + hard temporal
 * rules). Whatever the insurer hasn't configured (a 200 with an empty/null body — a real
 * answer, not a failure) keeps working off the baseline; that composition is unaffected by this
 * class's error handling.
 *
 * <p>Classification runs async: there's no user request/JWT on the thread, but the tenant schema is
 * propagated via {@link TenantContext}. So the call authenticates with a <b>service token</b> that
 * carries that schema — the same mechanism cases-service uses for its system-to-system calls, and
 * the reason rules-service exposes a role-free {@code /internal/fast-track} endpoint (the engine is
 * not a referente).
 *
 * <p><b>rules-service unreachable is NOT best-effort.</b> These endpoints carry configuration a
 * human (the referente) entered — Fast Track thresholds, exclusions, document agenda, hard rules —
 * and silently substituting the generic mock for that is worse than not classifying: a
 * claim could Fast Track or skip an exclusion using rules nobody at the insurer configured. So every
 * overlay but {@link #overlayScoring} lets the connectivity exception propagate instead of catching
 * it. That exception ({@code HttpServerErrorException}/{@code ResourceAccessException}) is exactly
 * what {@code ClaimClassificationService}'s {@code @Retryable} already retries on; once it gives up,
 * no result gets persisted, the case stays without a classification, and cases-service's own poller
 * ({@code ClassificationRefreshScheduler}) eventually marks it {@code CLASSIFICATION_FAILED} —
 * retryable by the analyst, same path as any other stuck classification. No new failure mode, no new
 * exception type: this reuses the retry/failure pipeline that already exists for exactly this case.
 *
 * <p>{@link #overlayScoring} is the one exception and stays best-effort on purpose: the fraud score
 * is a parallel signal that never gates a classification decision (see {@code BusinessRules
 * .ScoringConfig}'s javadoc), so losing it for one run isn't a reason to fail the whole thing.
 */
// Excluded when the "test" profile is active: ClassificationOrchestratorIntegrationTest
// (@ActiveProfiles("test")) runs the orchestrator synchronously and asserts against
// MockRulesAdapter's baseline (coverage-scoped Fast Track thresholds, the Hurto exclusion on
// coverage 1) — with this bean still @Primary there, it made a real HTTP call to whatever
// happened to be listening on rules-service's port (a leftover local/Railway container, if any),
// 401ing against a JWT_SECRET that doesn't match. Not a fake profile invented for this: it's the
// same "test" this test class already activates.
@Component
@Primary
@Profile("!test")
public class RulesRestAdapter implements RulesAdapter {

    private static final Logger log = LoggerFactory.getLogger(RulesRestAdapter.class);

    private final RestClient restClient;
    private final SecretKey jwtKey;
    private final MockRulesAdapter defaults;

    public RulesRestAdapter(
            @Value("${arbiter.rules-service.url:http://localhost:8081}") String rulesServiceUrl,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret,
            MockRulesAdapter defaults) {
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
        // Chained on purpose: if any of these five doesn't respond, none of them papers over it —
        // the exception propagates whole (see the class javadoc). Only overlayScoring, at the end,
        // stays best-effort.
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

    /**
     * The insurer's fraud-record policy. Insurer-wide, so no coverageId — same as the scoring
     * config, but <b>not</b> best-effort like it: this one can veto Fast Track, and swallowing an
     * outage would expedite a claim the insurer decided not to expedite. It rides the same
     * propagate-and-retry path as the other overlays (see the class javadoc).
     */
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
        if (rule == null || !rule.isEnabled()) {
            log.debug("[RulesRestAdapter] Fraud record rule off (or not configured) — records don't count");
            return BusinessRules.FraudRecordPolicy.disabled();
        }
        int windowMonths = rule.windowMonths() == null
                ? BusinessRules.FraudRecordPolicy.DEFAULT_WINDOW_MONTHS
                : rule.windowMonths();
        log.info("[RulesRestAdapter] Fraud record rule active — windowMonths={} blocksFastTrack={}",
                windowMonths, rule.vetoesFastTrack());
        return BusinessRules.FraudRecordPolicy.builder()
                .ruleId(rule.ruleId())
                .enabled(true)
                .windowMonths(windowMonths)
                .blocksFastTrack(rule.vetoesFastTrack())
                .build();
    }

    /**
     * The fraud score (factors + bands) the referente configures. A single config per insurer (not
     * per coverage), so it's read without a coverageId. Replaces the mock baseline when the insurer
     * has a config enabled; if it doesn't have one (or it can't be read), keeps the baseline's
     * reference scoring. This is what makes the referente's scoring panel actually affect the
     * classification (scoring used to always come from the mock).
     */
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

    /**
     * The coverage's intrinsic limits (report deadline D11, events-per-year cap D10, waiting
     * period D9), which the engine evaluates by code. No coverage configured (empty 200) keeps the
     * baseline; if rules-service doesn't respond, it propagates — see the class javadoc.
     */
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

    /**
     * The coverage's hard evaluable rules: hecho generador exclusions and the temporal ones the
     * insurer has active (D9/D10/D11/D12/D13, plus arrears). Replaces the baseline: if the insurer
     * configured something, theirs wins. An empty list (200 with no rows) is "not configured" and
     * keeps the baseline; if rules-service doesn't respond, it propagates — see the class javadoc.
     */
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

        // The Spanish criteria get replaced along with the thresholds, not merged: the mock's list
        // described different numbers and reached the prompt contradicting what the referente had
        // configured. If they saved a config with no criteria, the prompt goes without that
        // section — better that than resurrecting text nobody on the business side wrote (D14).
        if (ft.criteria() != null) {
            overlaid.fastTrackCriteria(List.copyOf(ft.criteria()));
        }
        return overlaid.build();
    }

    /**
     * What the referente writes in Coverages (exclusions) and Business Rules. Replaces the
     * baseline instead of adding to it: if the insurer configured their own rules, the mock's
     * generic ones don't matter. An empty list (200 with no text) is "not configured" and keeps
     * the baseline; if rules-service doesn't respond, it propagates — see the class javadoc.
     */
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
     * The document agenda the referente configured for the coverage's branch <b>and claim cause</b>.
     * Replaces the mock baseline: it's what the missing-docs gate ({@code checkRequiredDocuments})
     * compares against what the insured uploaded. An empty list (200 with no rows) is "not
     * configured" and keeps the baseline; if rules-service doesn't respond, it propagates — see the
     * class javadoc.
     *
     * <p>{@code claimCause} viaja como <b>nombre</b>, no como id: es lo único que trae el
     * {@code ClaimReport} que llega al motor, y rules-service lo resuelve a id contra el ramo de la
     * cobertura ({@code InternalDocumentRequirementService}). Es obligatorio desde que la agenda
     * documental se segmentó por hecho generador — antes bastaba el ramo, y omitirlo ahora devuelve
     * 400 y voltea la clasificación entera.
     */
    private BusinessRules overlayDocumentRequirements(BusinessRules rules, Long coverageId, String claimCause) {
        List<String> agenda = restClient.get()
                .uri(uri -> uri.path("/api/v1/rules/document-requirements/internal")
                        .queryParam("coverageId", coverageId)
                        .queryParam("claimCause", claimCause).build())
                .header(HttpHeaders.AUTHORIZATION, serviceToken())
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
        // Empty is an answer ("this claim cause needs no documents"), null is the absence of one
        // (unknown coverage or claim cause). Treating both as "not configured" meant a referente who
        // cleared every document from the panel still got the baseline's — see the null contract in
        // InternalDocumentRequirementService.
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

    /** Mirrors rules-service's RuleTextsDto (the referente's free-text lists for the branch). */
    private record RuleTextsResponse(List<String> exclusions, List<String> businessRules) {

        boolean isEmpty() {
            return (exclusions == null || exclusions.isEmpty())
                    && (businessRules == null || businessRules.isEmpty());
        }
    }

    /** Mirrors rules-service's EvaluableRulesDto (the coverage's hard evaluable rules). */
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
     * Mirrors rules-service's FraudRecordRuleDto (window + Fast Track veto). Boxed booleans, not
     * primitives: "this insurer configured nothing" is a 200 with an empty body, and a primitive
     * would turn that legitimate answer into a parse error — which, since this overlay propagates,
     * would sink the whole classification.
     */
    private record FraudRecordRuleResponse(
            Long ruleId, Boolean enabled, Integer windowMonths, Boolean blocksFastTrack) {

        boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        boolean vetoesFastTrack() {
            return Boolean.TRUE.equals(blocksFastTrack);
        }
    }

    /** Mirrors rules-service's CoverageLimitsDto (report deadline + events-per-year cap). */
    private record CoverageLimitsResponse(
            Long reportDeadlineHours, Integer maxEventsPerYear, Integer waitingPeriodDays,
            Boolean coversFamilyGroup, Boolean claimExhaustsCoverage) {

        boolean isEmpty() {
            return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null
                    && coversFamilyGroup == null && claimExhaustsCoverage == null;
        }
    }

    /** Mirrors rules-service's ScoringConfigDto (fraud-scoring factors + bands). */
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

    /** Mirrors rules-service's FastTrackConfigDto (JSON shape of the persisted thresholds). */
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
