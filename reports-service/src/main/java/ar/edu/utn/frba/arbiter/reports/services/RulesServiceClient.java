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
 * Lectura system-to-system de la configuración que administra rules-service. Hoy sólo el objetivo
 * de resolución, que es lo único de la configuración de la aseguradora que el tablero necesita.
 *
 * <p>Es la única cosa que este módulo NO lee directo de la base, y a propósito: las tablas que lee
 * sin pasar por REST ({@code cases}, {@code llm_analysis}) son datos operativos del esquema del
 * tenant, mientras que {@code insurer_rule} es la configuración que otro módulo administra, con su
 * propio historial y sus propias reglas de validación. Leerla por SQL sería atarse a un formato
 * JSONB que no es nuestro.
 *
 * <p>Firma un token de servicio propio en vez de reenviar el del usuario, igual que
 * {@code cases-service}: el tablero lo puede estar mirando un analista, y el endpoint del referente
 * le cerraría la puerta.
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
     * El objetivo de días que fijó la aseguradora, o {@link ResolutionTarget#UNSET} si no fijó
     * ninguno.
     *
     * <p><b>Un rules-service caído no voltea el tablero.</b> El objetivo es un agregado a una
     * tarjeta, no el tablero: si no se puede leer, la tarjeta muestra el tiempo promedio sin la
     * comparación, que es exactamente lo que muestra una aseguradora que nunca lo configuró. Dejar
     * caer toda la pantalla por eso sería desproporcionado.
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
            // El conteo lo pone el servicio: rules-service no sabe nada de expedientes.
            return new ResolutionTarget(true, response.targetDays(), 0);
        } catch (RestClientException unavailable) {
            log.warn("[Reports] could not read the resolution target, the dashboard goes without it: {}",
                    unavailable.getMessage());
            return ResolutionTarget.UNSET;
        }
    }

    /**
     * Espeja exactamente lo que devuelve rules-service ({@code ResolutionTargetDto}) y nada más.
     *
     * <p>No se deserializa directo sobre {@link ResolutionTarget} porque ese lleva además el
     * {@code exceeded}, que el otro módulo no manda ni tiene cómo saber: pedirle a Jackson que
     * arme un record con un componente que nunca viene en el JSON falla, y falla en tiempo de
     * ejecución. Mismo patrón que el {@code PolicyStandingRule} de cases-service.
     */
    private record Response(boolean enabled, Integer targetDays) {
    }
}
