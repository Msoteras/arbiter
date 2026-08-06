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
 * Primary {@link RulesAdapter}: reads the referente-configured Fast Track thresholds from
 * rules-service (the DB) and overlays them on the {@link MockRulesAdapter} baseline. rules-service
 * only persists the Fast Track thresholds so far; the interpretive rules/exclusions and the scoring
 * config still come from the mock, so we <b>compose</b> rather than replace — the prompt and the
 * fraud score keep working unchanged, only the Fast Track gate now honors the persisted config.
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
            log.warn("[RulesRestAdapter] rules-service unavailable for coverage {} — falling back to baseline: {}",
                    coverageId, e.getMessage());
            return base;
        }
    }

    private String serviceToken() {
        return "Bearer " + JwtSupport.issueServiceToken(jwtKey, "classification-service", TenantContext.get());
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
