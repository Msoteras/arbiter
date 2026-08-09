package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.adapters.mock.MockRulesAdapter;
import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.List;

/**
 * Primary {@link RulesAdapter}: reads what the referente configured in rules-service (the DB) and
 * overlays it on the {@link MockRulesAdapter} baseline — the Fast Track thresholds that gate the
 * expedited path, and the free-text rules/exclusions that go into the LLM prompt. The scoring
 * config still comes from the mock, so we <b>compose</b> rather than replace: whatever the insurer
 * hasn't configured keeps working off the baseline.
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
        // Dos lecturas independientes a propósito: si una falla, la otra igual se aplica. Cada una
        // cae a su parte del baseline sin tirar abajo la clasificación.
        return overlayRuleTexts(overlayFastTrack(base, coverageId), coverageId);
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
            return base.toBuilder()
                    .fastTrackThresholds(new BusinessRules.FastTrackThresholds(
                            ft.maxClaimedAmountRatio(), ft.maxPriorClaims(),
                            ft.requiresUpToDatePolicy(), ft.requiredDocumentTypes()))
                    .build();
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

    /** Mirrors rules-service's FastTrackConfigDto (JSON shape of the persisted thresholds). */
    private record FastTrackResponse(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            Boolean requiresUpToDatePolicy,
            List<String> requiredDocumentTypes) {

        boolean isEmpty() {
            return maxClaimedAmountRatio == null
                    && maxPriorClaims == null
                    && requiresUpToDatePolicy == null
                    && (requiredDocumentTypes == null || requiredDocumentTypes.isEmpty());
        }
    }
}
