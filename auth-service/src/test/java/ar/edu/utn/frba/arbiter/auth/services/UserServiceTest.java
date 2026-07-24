package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.Auth0ProvisioningException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotChangeOwnRoleException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotDeleteOwnAccountException;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEmailDomainException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidInviteTokenException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InviteTokenExpiredException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserAlreadyActiveException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private Auth0UserProvisioner auth0UserProvisioner;

    @Mock
    private EmailDomainValidator emailDomainValidator;

    @Mock
    private SendGridAdapter sendGridAdapter;

    private UserService userService;

    private UserService userService(Optional<Auth0UserProvisioner> provisioner) {
        return new UserService(userRepository, passwordEncoder, provisioner, emailDomainValidator, sendGridAdapter);
    }

    private CreateUserRequest analistaRequest() {
        return new CreateUserRequest(
                "nuevo.analista@arbiter.test", "Lucas", "Gómez",
                UserRole.ANALISTA_SINIESTROS, "Siniestros Celulares", LocalDate.of(2026, 1, 1));
    }

    @Test
    void createUser_validRequest_persistsPendingUserAndSendsInvite() {
        userService = userService(Optional.empty());
        when(userRepository.findByEmail("nuevo.analista@arbiter.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserResponse response = userService.createUser(analistaRequest());

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("nuevo.analista@arbiter.test");
        assertThat(response.rol()).isEqualTo(UserRole.ANALISTA_SINIESTROS);
        assertThat(response.estado()).isEqualTo(UserStatus.PENDING);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-value");
        assertThat(saved.getInviteToken()).isNotBlank();
        assertThat(saved.getInviteExpiresAt()).isAfter(Instant.now());

        verify(sendGridAdapter).send(eq("nuevo.analista@arbiter.test"), anyString(), anyString());
    }

    @Test
    void createUser_duplicateEmail_throwsEmailAlreadyExists() {
        userService = userService(Optional.empty());
        when(userRepository.findByEmail("nuevo.analista@arbiter.test"))
                .thenReturn(Optional.of(User.builder().id(1L).build()));

        assertThatThrownBy(() -> userService.createUser(analistaRequest()))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void createUser_roleOtherThanAnalista_throwsRoleNotAllowed() {
        userService = userService(Optional.empty());
        CreateUserRequest request = new CreateUserRequest(
                "asegurado@arbiter.test", "Martina", "Fernández",
                UserRole.ASEGURADO, "N/A", null);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void createUser_invalidEmailDomain_throwsInvalidEmailDomainAndNeverPersists() {
        userService = userService(Optional.empty());
        doThrow(new InvalidEmailDomainException("nuevo.analista@arbiter.test"))
                .when(emailDomainValidator).validate("nuevo.analista@arbiter.test");

        assertThatThrownBy(() -> userService.createUser(analistaRequest()))
                .isInstanceOf(InvalidEmailDomainException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_emailSendFails_rollsBackLocalUser() {
        userService = userService(Optional.empty());
        when(userRepository.findByEmail("nuevo.analista@arbiter.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        doThrow(new RuntimeException("SendGrid down")).when(sendGridAdapter).send(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> userService.createUser(analistaRequest()))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).delete(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
    }

    @Test
    void activateAccount_validToken_provisionsInAuth0AndSetsPassword() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS)
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));
        when(passwordEncoder.encode("NuevaPass123!")).thenReturn("hashed-real-password");

        userService.activateAccount("tok-123", "NuevaPass123!");

        verify(auth0UserProvisioner).createUser("nuevo.analista@arbiter.test", "NuevaPass123!");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-real-password");
        assertThat(captor.getValue().getInviteToken()).isNull();
        assertThat(captor.getValue().getInviteExpiresAt()).isNull();
        assertThat(captor.getValue().isActivated()).isTrue();
    }

    @Test
    void activateAccount_withoutAuth0Provider_stillSetsPasswordLocally() {
        userService = userService(Optional.empty());
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS)
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));
        when(passwordEncoder.encode("NuevaPass123!")).thenReturn("hashed-real-password");

        userService.activateAccount("tok-123", "NuevaPass123!");

        verify(userRepository).save(pending);
        assertThat(pending.getPasswordHash()).isEqualTo("hashed-real-password");
    }

    @Test
    void activateAccount_invalidToken_throwsInvalidInviteToken() {
        userService = userService(Optional.empty());
        when(userRepository.findByInviteToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activateAccount("bad-token", "NuevaPass123!"))
                .isInstanceOf(InvalidInviteTokenException.class);
    }

    @Test
    void activateAccount_expiredToken_throwsInviteTokenExpired() {
        userService = userService(Optional.empty());
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test")
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().minusSeconds(1))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> userService.activateAccount("tok-123", "NuevaPass123!"))
                .isInstanceOf(InviteTokenExpiredException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void activateAccount_auth0Fails_doesNotChangeLocalState() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test")
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));
        Auth0ProvisioningException failure =
                new Auth0ProvisioningException("crear", "nuevo.analista@arbiter.test", new RuntimeException("Auth0 down"));
        doThrow(failure).when(auth0UserProvisioner).createUser(anyString(), anyString());

        assertThatThrownBy(() -> userService.activateAccount("tok-123", "NuevaPass123!"))
                .isInstanceOf(Auth0ProvisioningException.class);

        verify(userRepository, never()).save(any());
        assertThat(pending.getInviteToken()).isEqualTo("tok-123");
    }

    @Test
    void requestPasswordReset_existingEmail_generatesTokenAndSendsMail() {
        userService = userService(Optional.empty());
        User existing = User.builder()
                .id(3L).email("analista.test@arbiter.test").nombre("Ana").rol(UserRole.ANALISTA_SINIESTROS)
                .build();
        when(userRepository.findByEmail("analista.test@arbiter.test")).thenReturn(Optional.of(existing));

        userService.requestPasswordReset("analista.test@arbiter.test");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getInviteToken()).isNotBlank();
        assertThat(captor.getValue().getInviteExpiresAt()).isAfter(Instant.now());
        verify(sendGridAdapter).send(eq("analista.test@arbiter.test"), anyString(), anyString());
    }

    @Test
    void requestPasswordReset_unknownEmail_doesNothing() {
        userService = userService(Optional.empty());
        when(userRepository.findByEmail("no-existe@arbiter.test")).thenReturn(Optional.empty());

        userService.requestPasswordReset("no-existe@arbiter.test");

        verify(userRepository, never()).save(any());
        verify(sendGridAdapter, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resetPassword_validToken_updatesAuth0AndLocalPassword() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User active = User.builder()
                .id(9L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS)
                .inviteToken("tok-456").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-456")).thenReturn(Optional.of(active));
        when(passwordEncoder.encode("OtraPass456!")).thenReturn("hashed-other-password");

        userService.resetPassword("tok-456", "OtraPass456!");

        verify(auth0UserProvisioner).updatePassword("analista.test@arbiter.test", "OtraPass456!");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-other-password");
        assertThat(captor.getValue().getInviteToken()).isNull();
        assertThat(captor.getValue().getInviteExpiresAt()).isNull();
    }

    @Test
    void resetPassword_invalidToken_throwsInvalidInviteToken() {
        userService = userService(Optional.empty());
        when(userRepository.findByInviteToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword("bad-token", "OtraPass456!"))
                .isInstanceOf(InvalidInviteTokenException.class);
    }

    @Test
    void resetPassword_expiredToken_throwsInviteTokenExpired() {
        userService = userService(Optional.empty());
        User active = User.builder()
                .id(9L).email("analista.test@arbiter.test")
                .inviteToken("tok-456").inviteExpiresAt(Instant.now().minusSeconds(1))
                .build();
        when(userRepository.findByInviteToken("tok-456")).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> userService.resetPassword("tok-456", "OtraPass456!"))
                .isInstanceOf(InviteTokenExpiredException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void checkToken_validToken_doesNotThrow() {
        userService = userService(Optional.empty());
        User active = User.builder()
                .id(9L).inviteToken("tok-789").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-789")).thenReturn(Optional.of(active));

        userService.checkToken("tok-789");
    }

    @Test
    void checkToken_invalidToken_throwsInvalidInviteToken() {
        userService = userService(Optional.empty());
        when(userRepository.findByInviteToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.checkToken("bad-token"))
                .isInstanceOf(InvalidInviteTokenException.class);
    }

    @Test
    void resendInvite_pendingUser_generatesNewTokenAndSendsMail() {
        userService = userService(Optional.empty());
        User pending = User.builder()
                .id(11L).email("pendiente.test@arbiter.test").nombre("Martina").rol(UserRole.ANALISTA_SINIESTROS)
                .inviteToken("tok-viejo").inviteExpiresAt(Instant.now().minusSeconds(1))
                .build();
        when(userRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.resendInvite(11L);

        assertThat(response.estado()).isEqualTo(UserStatus.PENDING);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getInviteToken()).isNotEqualTo("tok-viejo");
        assertThat(captor.getValue().getInviteExpiresAt()).isAfter(Instant.now());
        verify(sendGridAdapter).send(eq("pendiente.test@arbiter.test"), anyString(), anyString());
    }

    @Test
    void resendInvite_activeUser_throwsUserAlreadyActive() {
        userService = userService(Optional.empty());
        User active = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS)
                .activated(true).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> userService.resendInvite(7L))
                .isInstanceOf(UserAlreadyActiveException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void resendInvite_unknownUser_throwsUserNotFound() {
        userService = userService(Optional.empty());
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resendInvite(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateRole_validTarget_changesRole() {
        userService = userService(Optional.empty());
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.updateRole(7L, UserRole.REFERENTE_ASEGURADORA, "referente@arbiter.test");

        assertThat(response.rol()).isEqualTo(UserRole.REFERENTE_ASEGURADORA);
    }

    @Test
    void updateRole_ownAccount_throwsCannotChangeOwnRole() {
        userService = userService(Optional.empty());
        User self = User.builder().id(3L).email("referente@arbiter.test").rol(UserRole.REFERENTE_ASEGURADORA).build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> userService.updateRole(3L, UserRole.ANALISTA_SINIESTROS, "referente@arbiter.test"))
                .isInstanceOf(CannotChangeOwnRoleException.class);
    }

    @Test
    void updateRole_unknownUser_throwsUserNotFound() {
        userService = userService(Optional.empty());
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateRole(999L, UserRole.ASEGURADO, "referente@arbiter.test"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deleteUser_validTarget_deletesIt() {
        userService = userService(Optional.empty());
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        userService.deleteUser(7L, "referente@arbiter.test");

        verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_ownAccount_throwsCannotDeleteOwnAccount() {
        userService = userService(Optional.empty());
        User self = User.builder().id(3L).email("referente@arbiter.test").rol(UserRole.REFERENTE_ASEGURADORA).build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> userService.deleteUser(3L, "referente@arbiter.test"))
                .isInstanceOf(CannotDeleteOwnAccountException.class);
    }

    @Test
    void deleteUser_unknownUser_throwsUserNotFound() {
        userService = userService(Optional.empty());
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(999L, "referente@arbiter.test"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deleteUser_auth0ProviderConfigured_alsoDeletesFromAuth0() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        userService.deleteUser(7L, "referente@arbiter.test");

        verify(auth0UserProvisioner).deleteUser("analista.test@arbiter.test");
        verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_auth0DeletionFails_doesNotDeleteLocalUser() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        Auth0ProvisioningException failure =
                new Auth0ProvisioningException("borrar", "analista.test@arbiter.test", new RuntimeException("Auth0 down"));
        doThrow(failure).when(auth0UserProvisioner).deleteUser(any());

        assertThatThrownBy(() -> userService.deleteUser(7L, "referente@arbiter.test"))
                .isInstanceOf(Auth0ProvisioningException.class);

        verify(userRepository, never()).delete(any());
    }
}
