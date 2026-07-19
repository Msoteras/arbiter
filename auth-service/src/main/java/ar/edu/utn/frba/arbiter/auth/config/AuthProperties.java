package ar.edu.utn.frba.arbiter.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arbiter.auth")
public record AuthProperties(Jwt jwt, Login login) {

    public record Jwt(String secret, long expirationMinutes) {}

    public record Login(int maxFailedAttempts, long lockoutMinutes) {}
}
