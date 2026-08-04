package ar.edu.utn.frba.arbiter.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public final class JwtSupport {

    private static final Duration SERVICE_TOKEN_TTL = Duration.ofMinutes(5);

    private JwtSupport() {}

    public static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mints a short-lived token for system-to-system calls that don't happen on behalf of a
     * user request (e.g. a background scheduler polling another module) — there's no user JWT
     * to propagate in that case. No {@code rol} claim: the caller only needs to pass
     * {@code isAuthenticated()} on the receiving end, not any specific role.
     */
    public static String issueServiceToken(SecretKey key, String subject) {
        return issueServiceToken(key, subject, null);
    }

    /**
     * Same, carrying the tenant the call has to run against. A background job serves no single
     * insurer, so it sweeps them one at a time and names the schema explicitly — without this
     * claim the receiving module's TenantResolvingFilter finds nothing and falls back to the
     * common schema, where the per-tenant tables don't exist.
     *
     * @param tenantSchema schema to target, or {@code null} for calls that touch only the
     *                     common schema
     */
    public static String issueServiceToken(SecretKey key, String subject, String tenantSchema) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(SERVICE_TOKEN_TTL)));
        if (tenantSchema != null) {
            builder.claim("tenantSchema", tenantSchema);
        }
        return builder.signWith(key).compact();
    }
}
