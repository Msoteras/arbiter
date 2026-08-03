package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystDirectoryUnavailableException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Resolves analysts against auth-service over internal REST (no shared database — cada módulo es
 * dueño de sus tablas). Reads {@code GET /api/v1/auth/users/analysts}, the same listing the inbox
 * selector uses, so both ends agree on who is assignable.
 */
@Component
public class AuthServiceAnalystDirectory implements AnalystDirectory {

    /** Solo los campos que se usan acá; auth-service manda bastante más en cada UserResponse. */
    private record AnalystSummary(Long id, String nombre, String apellido) {
    }

    private final RestClient restClient;
    private final HttpServletRequest currentRequest;

    public AuthServiceAnalystDirectory(
            RestClient.Builder restClientBuilder,
            @Value("${arbiter.auth-service.url:http://auth-service:8080}") String authServiceUrl,
            HttpServletRequest currentRequest
    ) {
        this.restClient = restClientBuilder.baseUrl(authServiceUrl).build();
        this.currentRequest = currentRequest;
    }

    @Override
    public String analystName(Long analystId) {
        return analysts().stream()
                .filter(analyst -> Objects.equals(analyst.id(), analystId))
                .findFirst()
                .map(analyst -> analyst.nombre() + " " + analyst.apellido())
                .orElseThrow(() -> new AnalystNotFoundException(analystId));
    }

    /**
     * Assignment always happens inside a user's request (an analyst or the referente clicking
     * assign), so the caller's JWT is forwarded as-is instead of minting a service token — they
     * already passed {@code @PreAuthorize} here with it, and auth-service gates this same listing
     * on those two roles.
     */
    private List<AnalystSummary> analysts() {
        try {
            AnalystSummary[] response = restClient.get()
                    .uri("/api/v1/auth/users/analysts")
                    .header(HttpHeaders.AUTHORIZATION, currentRequest.getHeader(HttpHeaders.AUTHORIZATION))
                    .retrieve()
                    .body(AnalystSummary[].class);
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RestClientException exception) {
            // Assignment is a write: better to fail loudly than to persist an owner we couldn't verify.
            throw new AnalystDirectoryUnavailableException(exception);
        }
    }
}
