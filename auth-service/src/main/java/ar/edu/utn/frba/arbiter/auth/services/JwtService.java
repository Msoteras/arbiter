package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues the arbiter JWT. Callers only depend on {@link #issue}, so a future Auth0-issued
 * token would only change this class (and its secret/audience config).
 */
@Component
@RequiredArgsConstructor
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties properties;
    private SecretKey key;

    @PostConstruct
    void init() {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            log.warn("JWT_SECRET no configurado: generando una clave efímera para este proceso. "
                    + "Los tokens no sobreviven un reinicio ni se comparten entre instancias — "
                    + "configurar JWT_SECRET fuera de dev.");
            key = Jwts.SIG.HS256.key().build();
            return;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes para HS256");
        }
        key = JwtSupport.key(secret);
    }

    /** Misma clave usada para firmar — SecurityConfig la reutiliza para validar (nunca la deriva por su cuenta). */
    public SecretKey getKey() {
        return key;
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    /**
     * Every claim here used to be read straight off {@link User}; now that name/last name
     * and the role live on the per-tenant profile and {@code user_role} respectively, the
     * caller (AuthService) resolves them first and hands over the result — JwtService just
     * encodes it, it doesn't know about tenants or profile tables.
     *
     * {@code insurerIds} lists every insurer the user belongs to; {@code tenantSchema} is
     * the one this session actually resolved to (see TenantResolver — first insurer wins
     * until the multi-insurer login UX is decided, per README-multitenant.md).
     */
    public IssuedToken issue(
            User user, UserRole rol, String nombre, String apellido, String insuredId,
            List<Long> insurerIds, String tenantSchema
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.jwt().expirationMinutes()));

        var builder = Jwts.builder()
                .subject(user.getEmail())
                .claim("rol", rol.name())
                .claim("nombre", nombre)
                .claim("apellido", apellido);
        if (insuredId != null) {
            builder.claim("insuredId", insuredId);
        }
        if (!insurerIds.isEmpty()) {
            builder.claim("insurerIds", insurerIds);
        }
        if (tenantSchema != null) {
            builder.claim("tenantSchema", tenantSchema);
        }
        String token = builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        return new IssuedToken(token, expiresAt);
    }
}
