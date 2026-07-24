package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.exceptions.AccountLockedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidCredentialsException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseCredentialsAuthenticatorTest {

    private static final String EMAIL = "analista@arbiter.test";
    private static final String PASSWORD = "changeme123";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DatabaseCredentialsAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                "database",
                new AuthProperties.Jwt("test-secret", 60),
                new AuthProperties.Login(5, 15),
                null,
                null);
        authenticator = new DatabaseCredentialsAuthenticator(userRepository, passwordEncoder, properties);
    }

    private User newUser() {
        return User.builder()
                .id(1L)
                .email(EMAIL)
                .passwordHash("hashed")
                .nombre("Lucas")
                .apellido("Gómez")
                .rol(UserRole.ANALISTA_SINIESTROS)
                .failedAttempts(0)
                .build();
    }

    @Test
    void authenticate_validCredentials_resetsFailedAttemptsAndReturnsUser() {
        User user = newUser();
        user.setFailedAttempts(3);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "hashed")).thenReturn(true);

        User result = authenticator.authenticate(EMAIL, PASSWORD);

        assertThat(result).isSameAs(user);
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void authenticate_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_wrongPassword_incrementsFailedAttemptsWithoutLocking() {
        User user = newUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void authenticate_fifthConsecutiveFailure_locksAccountFor15Minutes() {
        User user = newUser();
        user.setFailedAttempts(4);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void authenticate_accountCurrentlyLocked_throwsAccountLockedEvenWithCorrectPassword() {
        User user = newUser();
        user.setLockedUntil(Instant.now().plusSeconds(600));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, PASSWORD))
                .isInstanceOf(AccountLockedException.class);
    }
}
