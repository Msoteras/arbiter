package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.common.models.entities.User;

/** Validates credentials and returns the authenticated user. {@link Auth0Adapter} is the only implementation. */
public interface CredentialsAuthenticator {
    User authenticate(String email, String rawPassword);
}
