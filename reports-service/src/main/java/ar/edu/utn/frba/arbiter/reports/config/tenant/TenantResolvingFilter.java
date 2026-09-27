package ar.edu.utn.frba.arbiter.reports.config.tenant;

import ar.edu.utn.frba.arbiter.reports.config.RequesterContext;
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
 * Sets {@link TenantContext} from the JWT's {@code tenantSchema} claim, and {@link RequesterContext}
 * from the name and role beside it. Parses the token itself because common-lib's
 * JwtAuthenticationFilter only keeps the role, not the full claim set — and parses it once for both,
 * rather than leaving a second filter to drift out of step with this one.
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
                    RequesterContext.set(new RequesterContext.Requester(
                            fullName(claims), claims.get("rol", String.class)));
                } catch (JwtException | IllegalArgumentException ex) {
                    // Invalid token: JwtAuthenticationFilter already left the request
                    // unauthenticated, so authorization rejects it downstream.
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            RequesterContext.clear();
        }
    }

    /**
     * The token carries the name split in two, as the rest of the platform stores it.
     *
     * @return null when neither half is present, e.g. a service token issued by a scheduled job
     */
    private static String fullName(Claims claims) {
        String name = claims.get("nombre", String.class);
        String surname = claims.get("apellido", String.class);
        if (name == null && surname == null) {
            return null;
        }
        return ((name == null ? "" : name) + " " + (surname == null ? "" : surname)).strip();
    }

}
