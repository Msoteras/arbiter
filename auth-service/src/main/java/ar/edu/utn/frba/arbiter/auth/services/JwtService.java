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
import java.util.List;

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
        return issue(user, List.of());
    }

    /**
     * {@code insurerIds} son las aseguradoras a las que pertenece el usuario
     * ("usuario_aseguradora" en el DER) — insumo para la resolución de tenant por request
     * (decisión #10) una vez que exista. Un usuario puede estar en más de una (ver
     * README-multitenant.md); qué hace el login en ese caso todavía no está decidido, así
     * que el claim va como lista y no se fuerza a un solo valor.
     */
    public IssuedToken issue(User user, List<Long> insurerIds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.jwt().expirationMinutes()));

        var builder = Jwts.builder()
                .subject(user.getEmail())
                .claim("rol", user.getRol().name())
                .claim("nombre", user.getNombre())
                .claim("apellido", user.getApellido());
        // Solo los asegurados tienen insuredId; el back destino lo puede leer del token
        // en vez de recibirlo por parámetro cuando se integre Auth0.
        if (user.getInsuredId() != null) {
            builder.claim("insuredId", user.getInsuredId());
        }
        if (!insurerIds.isEmpty()) {
            builder.claim("insurerIds", insurerIds);
        }
        String token = builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();

        return new IssuedToken(token, expiresAt);
    }
}
