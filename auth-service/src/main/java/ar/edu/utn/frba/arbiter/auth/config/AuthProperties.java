package ar.edu.utn.frba.arbiter.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arbiter.auth")
public record AuthProperties(String provider, Jwt jwt, Login login, Auth0 auth0) {

    public record Jwt(String secret, long expirationMinutes) {}

    public record Login(int maxFailedAttempts, long lockoutMinutes) {}

    /** Credentials for the "login" Auth0 application (Regular Web App, Password grant). */
    public record Auth0(String domain, String clientId, String clientSecret, String connection) {}
}
