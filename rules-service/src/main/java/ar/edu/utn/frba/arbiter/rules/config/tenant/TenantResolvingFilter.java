package ar.edu.utn.frba.arbiter.rules.config.tenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;

/**
 * Sets {@link TenantContext} for the duration of a request, from the JWT's
 * {@code tenantSchema} claim (set at login by auth-service once it resolves which insurer
 * to use). Parses the token independently of {@code common-lib}'s JwtAuthenticationFilter
 * — that one only keeps the role, not the full claim set — same trade-off documented in
 * auth-service's and cases-service's copies of this filter.
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
                } catch (JwtException | IllegalArgumentException ex) {
                    // Invalid/expired token: JwtAuthenticationFilter already left the request
                    // unauthenticated, so downstream authorization rejects it — nothing to do
                    // here beyond not resolving a tenant.
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
