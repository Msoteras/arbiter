package ar.edu.utn.frba.arbiter.cases.config.tenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;

/**
 * Sets {@link TenantContext} and {@link CallerContext} for the duration of a request, from the
 * JWT's {@code tenantSchema}, {@code insuredId} and {@code insurerIds} claims (set at login by
 * auth-service once it resolves which insurer to use).
 * Parses the token independently of {@code common-lib}'s JwtAuthenticationFilter
 * — that one only keeps the role, not the full claim set, and duplicating one field's
 * worth of parsing here is cheaper than changing a class shared by all 5 modules.
 *
 * <p>Same implementation as auth-service's, minus its login carve-out: every endpoint in
 * this module requires a token already, so there is no request that has to resolve a
 * tenant without one.
 */
public class TenantResolvingFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public TenantResolvingFilter(SecretKey key) {
        this.key = key;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = Jwts.parser().verifyWith(key).build()
                            .parseSignedClaims(header.substring(7))
                            .getPayload();
                    String tenantSchema = claims.get("tenantSchema", String.class);
                    if (tenantSchema != null) {
                        TenantContext.set(tenantSchema);
                    }
                    CallerContext.set(new CallerContext.Caller(
                            claims.get("insuredId", String.class), insurerIds(claims), tenantSchema));
                } catch (JwtException | IllegalArgumentException ex) {
                    // Invalid/expired token: JwtAuthenticationFilter already left the request
                    // unauthenticated, so downstream authorization rejects it — nothing to do
                    // here beyond not resolving a tenant.
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            CallerContext.clear();
        }
    }

    /**
     * Jackson deserializes the claim's numbers as Integer when they fit, so this normalizes to
     * Long rather than casting — a blind {@code (List<Long>)} blows up at first use.
     */
    private static List<Long> insurerIds(Claims claims) {
        Object raw = claims.get("insurerIds");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue())
                .toList();
    }
}
