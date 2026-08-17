package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * Puerta de cases-service al motor de reglas (rules-service) por REST interno. Hoy solo lee las
 * inclusiones de cobertura para el wizard de alta de denuncia; se llama dentro del mismo request
 * del asegurado, así que reenvía su JWT tal cual — salvo que la operación se haya movido al tenant
 * de la aseguradora que emitió la póliza (asegurado con pólizas en dos compañías), en cuyo caso
 * firma un token de servicio con ese tenant, igual que {@link ClassificationServiceClient}.
 */
@Component
public class RuleServiceClient {

    private final RestClient restClient;
    private final HttpServletRequest currentRequest;
    private final SecretKey jwtKey;

    public RuleServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.rules-service.url:http://rules-service:8081}") String rulesServiceUrl,
            HttpServletRequest currentRequest,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret) {
        this.restClient = restClientBuilder.baseUrl(rulesServiceUrl).build();
        this.currentRequest = currentRequest;
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    /** Nombres de los hechos generadores que la cobertura SÍ cubre (whitelist vacía ⇒ lista vacía). */
    public List<String> coveredClaimCauses(Long coverageId) {
        List<String> names = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/rules/coverage-inclusions/covered-claim-causes")
                        .queryParam("coverageId", coverageId)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, authorizationHeaderForCurrentTenant())
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
        return names == null ? List.of() : names;
    }

    private String authorizationHeaderForCurrentTenant() {
        if (!CallerContext.get().movedAwayFromHome()) {
            return currentRequest.getHeader(HttpHeaders.AUTHORIZATION);
        }
        return "Bearer " + JwtSupport.issueServiceToken(
                jwtKey, "cases-service-covered-causes", TenantContext.get());
    }
}
