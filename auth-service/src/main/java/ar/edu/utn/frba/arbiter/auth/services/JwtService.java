package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
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

/**
 * Issues the arbiter JWT. Callers only depend on {@link #issue(User)} — if Auth0 replaces this
 * later, only this class (and its Auth0 secret/audience config) changes.
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

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.jwt().expirationMinutes()));

        String token = Jwts.builder()
                .subject(user.getEmail())
                .claim("rol", user.getRol().name())
                .claim("nombre", user.getNombre())
                .claim("apellido", user.getApellido())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        return new IssuedToken(token, expiresAt);
    }
}
