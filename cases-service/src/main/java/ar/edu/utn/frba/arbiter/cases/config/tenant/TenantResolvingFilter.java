package ar.edu.utn.frba.arbiter.cases.config.tenant;

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
import java.util.List;

/**
 * Parses the token on its own because common-lib's {@code JwtAuthenticationFilter} only keeps the
 * role, and widening it would change a class shared by every module.
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
                    // Invalid token: JwtAuthenticationFilter already left the request unauthenticated.
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            CallerContext.clear();
        }
    }

    /** Jackson yields Integer when the number fits, so a blind {@code (List<Long>)} cast would blow up. */
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
