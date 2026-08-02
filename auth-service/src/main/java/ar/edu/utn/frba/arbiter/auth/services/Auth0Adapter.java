package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.exceptions.AccountLockedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidCredentialsException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Validates credentials against Auth0 (Resource Owner Password Grant, "password-realm" against
 * the Username-Password-Authentication connection) — Auth0 is the only place a password lives
 * now, the multi-tenant {@code users} table doesn't have a hash column at all. The account
 * lockout counters stay local (Auth0 never sees them).
 */
@Component
@ConditionalOnProperty(prefix = "arbiter.auth", name = "provider", havingValue = "auth0")
@RequiredArgsConstructor
public class Auth0Adapter implements CredentialsAuthenticator {

    private final AuthAPI auth0AuthApi;
    private final UserRepository userRepository;
    private final AuthProperties properties;

    @Override
    public User authenticate(String email, String rawPassword) {
        User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        try {
            auth0AuthApi.login(email, rawPassword.toCharArray(), properties.auth0().connection()).execute();
        } catch (Auth0Exception e) {
            registerFailedAttempt(user);
            throw new InvalidCredentialsException();
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        return user;
    }

    private void registerFailedAttempt(User user) {
        user.setFailedAttempts(user.getFailedAttempts() + 1);
        if (user.getFailedAttempts() >= properties.login().maxFailedAttempts()) {
            user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(properties.login().lockoutMinutes())));
        }
        userRepository.save(user);
    }
}
