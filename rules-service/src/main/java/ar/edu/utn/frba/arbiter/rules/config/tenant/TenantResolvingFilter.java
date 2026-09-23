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
 * Sets {@link TenantContext} for the request from the JWT's {@code tenantSchema} claim. Parses the
 * token itself because common-lib's JwtAuthenticationFilter only keeps the role, not the claims.
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
                    // JwtAuthenticationFilter already left the request unauthenticated, so
                    // authorization rejects it downstream; just don't resolve a tenant.
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
