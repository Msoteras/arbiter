package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.exceptions.AccountLockedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidCredentialsException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.net.TokenRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Auth0AdapterTest {

    private static final String EMAIL = "analista@arbiter.test";
    private static final String PASSWORD = "changeme123";
    private static final String CONNECTION = "Username-Password-Authentication";

    @Mock
    private AuthAPI auth0AuthApi;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenRequest tokenRequest;

    private Auth0Adapter authenticator;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                "auth0",
                new AuthProperties.Jwt("test-secret", 60),
                new AuthProperties.Login(5, 15),
                new AuthProperties.Auth0("example.auth0.com", "client-id", "client-secret", CONNECTION),
                null);
        authenticator = new Auth0Adapter(auth0AuthApi, userRepository, properties);
    }

    private User newUser() {
        return User.builder()
                .id(1L)
                .email(EMAIL)
                .passwordHash("unused-auth0-validates-this")
                .nombre("Lucas")
                .apellido("Gómez")
                .rol(UserRole.ANALISTA_SINIESTROS)
                .failedAttempts(0)
                .build();
    }

    @Test
    void authenticate_auth0AcceptsCredentials_resetsFailedAttemptsAndReturnsLocalUser() throws Auth0Exception {
        User user = newUser();
        user.setFailedAttempts(3);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(auth0AuthApi.login(eq(EMAIL), any(char[].class), eq(CONNECTION))).thenReturn(tokenRequest);
        when(tokenRequest.execute()).thenReturn(null);

        User result = authenticator.authenticate(EMAIL, PASSWORD);

        assertThat(result).isSameAs(user);
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void authenticate_unknownEmail_throwsInvalidCredentialsWithoutCallingAuth0() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_auth0RejectsCredentials_incrementsFailedAttemptsWithoutLocking() throws Auth0Exception {
        User user = newUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(auth0AuthApi.login(eq(EMAIL), any(char[].class), eq(CONNECTION))).thenReturn(tokenRequest);
        when(tokenRequest.execute()).thenThrow(new Auth0Exception("Wrong email or password"));

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void authenticate_fifthConsecutiveFailure_locksAccountFor15Minutes() throws Auth0Exception {
        User user = newUser();
        user.setFailedAttempts(4);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(auth0AuthApi.login(eq(EMAIL), any(char[].class), eq(CONNECTION))).thenReturn(tokenRequest);
        when(tokenRequest.execute()).thenThrow(new Auth0Exception("Wrong email or password"));

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void authenticate_accountCurrentlyLocked_throwsAccountLockedWithoutCallingAuth0() {
        User user = newUser();
        user.setLockedUntil(Instant.now().plusSeconds(600));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authenticator.authenticate(EMAIL, PASSWORD))
                .isInstanceOf(AccountLockedException.class);
    }
}
