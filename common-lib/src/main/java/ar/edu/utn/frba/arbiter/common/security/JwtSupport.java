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
     * Short-lived token for system-to-system calls with no user JWT to propagate (e.g. a
     * scheduler). No {@code rol} claim: the receiver only checks {@code isAuthenticated()}.
     */
    public static String issueServiceToken(SecretKey key, String subject) {
        return issueServiceToken(key, subject, null);
    }

    /**
     * Carries the tenant explicitly: a background job serves no single insurer, and without the
     * claim the receiver falls back to the common schema, where tenant tables don't exist.
     *
     * @param tenantSchema {@code null} for calls that touch only the common schema
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
