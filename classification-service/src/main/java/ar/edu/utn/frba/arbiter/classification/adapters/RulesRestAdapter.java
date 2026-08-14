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
 * expedited path, the free-text rules/exclusions that go into the LLM prompt, and the document
 * agenda that the missing-docs gate checks. The scoring config still comes from the mock, so we
 * <b>compose</b> rather than replace: whatever the insurer hasn't configured keeps working off the
 * baseline.
 *
 * <p>Classification runs async: there's no user request/JWT on the thread, but the tenant schema is
 * propagated via {@link TenantContext}. So the call authenticates with a <b>service token</b> that
 * carries that schema — the same mechanism cases-service uses for its system-to-system calls, and
 * the reason rules-service exposes a role-free {@code /internal/fast-track} endpoint (the engine is
 * not a referente). rules-service is best-effort: on any failure we fall back to the mock baseline
 * and never break the classification.
 */
@Component
@Primary
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
        // Lecturas independientes a propósito: si una falla, las otras igual se aplican. Cada una
        // cae a su parte del baseline sin tirar abajo la clasificación.
        return overlayScoring(
                overlayCoverageLimits(
                        overlayEvaluableRules(
                                overlayDocumentRequirements(
                                        overlayRuleTexts(overlayFastTrack(base, coverageId), coverageId),
                                        coverageId, claimCauseId),
                                coverageId),
                        coverageId));
    }

    /**
     * El scoring de fraude (factores + bandas) que configura el referente. Es config única por
     * aseguradora (no por cobertura), así que se lee sin coverageId. Reemplaza al baseline del mock
     * cuando la aseguradora tiene una config habilitada; si no la tiene (o no se puede leer), deja el
     * scoring de referencia del baseline. Esto es lo que hace que el panel de scoring del referente
     * efectivamente afecte la clasificación (antes el scoring salía siempre del mock).
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
     * Los límites intrínsecos de la cobertura (plazo de denuncia D11, tope de eventos por año D10),
     * que el motor evalúa por código. Best-effort como el resto; si no se pueden leer, deja el
     * baseline.
     */
    private BusinessRules overlayCoverageLimits(BusinessRules rules, Long coverageId) {
        try {
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
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for coverage limits of coverage {} — baseline: {}",
                    coverageId, e.getMessage());
            return rules;
        }
    }

    /**
     * Las reglas duras evaluables de la cobertura (hoy: exclusiones de hecho generador). Reemplaza al
     * baseline: si la aseguradora configuró exclusiones, mandan las suyas. Una lista vacía se trata
     * como "no configurado" y deja el baseline.
     *
     * <p>Best-effort como los otros overlays, con un matiz que la clasificación no puede ignorar: si
     * rules-service no responde, el resultado <b>no puede ser "pasa igual"</b> — pero el corte
     * degradado (no aprobar por silencio del motor, derivar a revisión) todavía no está: por ahora
     * cae al baseline como el resto. Queda anotado como pendiente en plan-reglas-evaluables.md §3.
     */
    private BusinessRules overlayEvaluableRules(BusinessRules rules, Long coverageId) {
        try {
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
                            .includedClaimCauseIds(r.includedClaimCauseIds())
                            .build())
                    .toList();
            log.info("[RulesRestAdapter] Evaluable rules loaded from rules-service for coverage {} — {} rules",
                    coverageId, mapped.size());
            return rules.toBuilder().evaluableRules(mapped).build();
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for evaluable rules of coverage {} — baseline: {}",
                    coverageId, e.getMessage());
            return rules;
        }
    }

    private BusinessRules overlayFastTrack(BusinessRules base, Long coverageId) {
        try {
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

            // Los criterios en castellano se reemplazan junto con los umbrales, no se mezclan: la
            // lista del mock describía otros números y llegaba al prompt contradiciendo a los que el
            // referente había configurado. Si guardó una config sin criterios, el prompt va sin la
            // sección — mejor eso que resucitar un texto que nadie del negocio escribió (D14).
            if (ft.criteria() != null) {
                overlaid.fastTrackCriteria(List.copyOf(ft.criteria()));
            }
            return overlaid.build();
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for Fast Track of coverage {} — baseline: {}",
                    coverageId, e.getMessage());
            return base;
        }
    }

    /**
     * Lo que el referente escribe en Coberturas (exclusiones) y Reglas de negocio. Reemplaza al
     * baseline en vez de sumarse: si la aseguradora configuró sus reglas, las genéricas del mock no
     * pintan nada. Una lista vacía se trata como "no configurado" y deja el baseline.
     */
    private BusinessRules overlayRuleTexts(BusinessRules rules, Long coverageId) {
        try {
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
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for rule texts of coverage {} — baseline: {}",
                    coverageId, e.getMessage());
            return rules;
        }
    }

    /**
     * La agenda documental que el referente configuró para el ramo + hecho generador de la cobertura.
     * Reemplaza al baseline del mock: es lo que el gate de faltantes ({@code checkRequiredDocuments})
     * compara contra lo que el asegurado subió. Una lista vacía = "no configurado" y deja el baseline.
     */
    private BusinessRules overlayDocumentRequirements(BusinessRules rules, Long coverageId, String claimCause) {
        try {
            List<String> agenda = restClient.get()
                    .uri(uri -> uri.path("/api/v1/rules/document-requirements/internal")
                            .queryParam("coverageId", coverageId)
                            .queryParam("claimCause", claimCause).build())
                    .header(HttpHeaders.AUTHORIZATION, serviceToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {});
            if (agenda == null || agenda.isEmpty()) {
                log.debug("[RulesRestAdapter] No document agenda in DB for coverage {} / claim cause {} — using baseline",
                        coverageId, claimCause);
                return rules;
            }
            log.info("[RulesRestAdapter] Document agenda loaded from rules-service for coverage {} / claim cause {} — {} docs",
                    coverageId, claimCause, agenda.size());
            return rules.toBuilder().requiredDocumentTypes(agenda).build();
        } catch (Exception e) {
            log.warn("[RulesRestAdapter] rules-service unavailable for document agenda of coverage {} / claim cause {} — baseline: {}",
                    coverageId, claimCause, e.getMessage());
            return rules;
        }
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
            List<Long> includedClaimCauseIds) {}

    /** Mirrors rules-service's CoverageLimitsDto (plazo de denuncia + tope de eventos por año). */
    private record CoverageLimitsResponse(
            Long reportDeadlineHours, Integer maxEventsPerYear, Integer waitingPeriodDays,
            Boolean coversFamilyGroup, Boolean claimExhaustsCoverage) {

        boolean isEmpty() {
            return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null
                    && coversFamilyGroup == null && claimExhaustsCoverage == null;
        }
    }

    /** Mirrors rules-service's ScoringConfigDto (factores + bandas del scoring de fraude). */
    private record ScoringResponse(Long id, boolean enabled, boolean fullAnalysisOnFastTrack,
                                   List<ScoringFactorJson> factors, List<ScoringBandJson> bands) {

        /** Mapea al ScoringConfig de classification; null si alguna banda no es una RiskBand válida. */
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
