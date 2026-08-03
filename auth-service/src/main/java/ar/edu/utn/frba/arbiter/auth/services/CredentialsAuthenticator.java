package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.common.models.entities.User;

/**
 * Validates credentials and returns the authenticated user. This is the seam for the future
 * Auth0 swap: a new implementation replaces {@link DatabaseCredentialsAuthenticator} and
 * AuthController / AuthService / JwtService don't change.
 */
public interface CredentialsAuthenticator {
    User authenticate(String email, String rawPassword);
}
