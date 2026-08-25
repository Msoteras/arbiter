package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
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
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.RoleRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long CALLER_INSURER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserInsurerRepository userInsurerRepository;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private TenantProfileService tenantProfileService;

    @Mock
    private Auth0UserProvisioner auth0UserProvisioner;

    @Mock
    private EmailDomainValidator emailDomainValidator;

    @Mock
    private SendGridAdapter sendGridAdapter;

    @Mock
    private PasswordCipher passwordCipher;

    @Mock
    private InsuredProvisioningService insuredProvisioningService;

    @Mock
    private AuthService authService;

    private UserService userService;

    /**
     * Sealing the envelope is PasswordCipherTest's job; here it just passes the password through so
     * these tests keep reading as "activate with this password". Lenient because most never get to it.
     */
    @BeforeEach
    void passwordCipherPassesThrough() {
        lenient().when(passwordCipher.decrypt(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserService userService(Optional<Auth0UserProvisioner> provisioner) {
        return new UserService(userRepository, claimsAnalystRepository, roleRepository,
                userInsurerRepository, tenantResolver, tenantProfileService, provisioner,
                emailDomainValidator, sendGridAdapter, passwordCipher, insuredProvisioningService,
                authService);
    }

    private CreateUserRequest analistaRequest() {
        return new CreateUserRequest("nuevo.analista@arbiter.test", "Lucas", "Gómez", UserRole.ANALISTA_SINIESTROS);
    }

    private Role analystRole() {
        return Role.builder().id(2L).code("ANALISTA_SINIESTROS").name("Analista de siniestros").build();
    }

    private User caller() {
        return User.builder().id(3L).email("referente@arbiter.test").build();
    }

    /** Stubs the referente-lookup path createUser/listUsers both go through. */
    private void stubCaller() {
        when(userRepository.findByEmail("referente@arbiter.test")).thenReturn(Optional.of(caller()));
        when(tenantResolver.insurerIdsFor(3L)).thenReturn(java.util.List.of(CALLER_INSURER_ID));
    }

    @Test
    void createUser_validRequest_persistsPendingUserAndSendsInvite() {
        userService = userService(Optional.empty());
        stubCaller();
        when(userRepository.findByEmail("nuevo.analista@arbiter.test")).thenReturn(Optional.empty());
        when(roleRepository.findByCode("ANALISTA_SINIESTROS")).thenReturn(Optional.of(analystRole()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserResponse response = userService.createUser(analistaRequest(), "referente@arbiter.test");

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("nuevo.analista@arbiter.test");
        assertThat(response.nombre()).isEqualTo("Lucas");
        assertThat(response.apellido()).isEqualTo("Gómez");
        assertThat(response.rol()).isEqualTo(UserRole.ANALISTA_SINIESTROS);
        assertThat(response.estado()).isEqualTo(UserStatus.PENDING);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getInviteToken()).isNotBlank();
        assertThat(saved.getInviteExpiresAt()).isAfter(Instant.now());
        assertThat(saved.getAuth0Sub()).startsWith("pending:");

        verify(userInsurerRepository).save(any(UserInsurer.class));
        verify(tenantProfileService).createClaimsAnalyst(saved, "Lucas", "Gómez", "nuevo.analista@arbiter.test");
        verify(sendGridAdapter).send(eq("nuevo.analista@arbiter.test"), anyString(), anyString());
    }

    @Test
    void createUser_duplicateEmail_throwsEmailAlreadyExists() {
        userService = userService(Optional.empty());
        when(userRepository.findByEmail("nuevo.analista@arbiter.test"))
                .thenReturn(Optional.of(User.builder().id(1L).build()));

        assertThatThrownBy(() -> userService.createUser(analistaRequest(), "referente@arbiter.test"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void createUser_roleOtherThanAnalista_throwsRoleNotAllowed() {
        userService = userService(Optional.empty());
        CreateUserRequest request = new CreateUserRequest(
                "asegurado@arbiter.test", "Martina", "Fernández", UserRole.ASEGURADO);

        assertThatThrownBy(() -> userService.createUser(request, "referente@arbiter.test"))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void createUser_invalidEmailDomain_throwsInvalidEmailDomainAndNeverPersists() {
        userService = userService(Optional.empty());
        doThrow(new InvalidEmailDomainException("nuevo.analista@arbiter.test"))
                .when(emailDomainValidator).validate("nuevo.analista@arbiter.test");

        assertThatThrownBy(() -> userService.createUser(analistaRequest(), "referente@arbiter.test"))
                .isInstanceOf(InvalidEmailDomainException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_emailSendFails_rollsBackLocalUser() {
        userService = userService(Optional.empty());
        stubCaller();
        when(userRepository.findByEmail("nuevo.analista@arbiter.test")).thenReturn(Optional.empty());
        when(roleRepository.findByCode("ANALISTA_SINIESTROS")).thenReturn(Optional.of(analystRole()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        doThrow(new RuntimeException("SendGrid down")).when(sendGridAdapter).send(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> userService.createUser(analistaRequest(), "referente@arbiter.test"))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).delete(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
    }

    private LoginResponse dummySession() {
        return new LoginResponse("signed.jwt.token", Instant.now().plusSeconds(3600), 9L,
                "nuevo.analista@arbiter.test", UserRole.ANALISTA_SINIESTROS, "Lucas", "Gómez", null, null);
    }

    @Test
    void activateAccount_validToken_provisionsInAuth0AndSetsAuthSub() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test")
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));
        when(auth0UserProvisioner.createUser("nuevo.analista@arbiter.test", "NuevaPass123!"))
                .thenReturn("auth0|new-user-id");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        LoginResponse session = dummySession();
        when(authService.issueSessionFor(any(User.class))).thenReturn(session);

        LoginResponse result = userService.activateAccount("tok-123", "NuevaPass123!");

        // The session comes from AuthService.issueSessionFor, not built here — activation's job
        // is establishing the account, not resolving roles/tenant/JWT a second way.
        assertThat(result).isSameAs(session);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAuth0Sub()).isEqualTo("auth0|new-user-id");
        assertThat(captor.getValue().getInviteToken()).isNull();
        assertThat(captor.getValue().getInviteExpiresAt()).isNull();
        assertThat(captor.getValue().isActivated()).isTrue();
        // Issued off the SAVED user, not the pre-activation one — otherwise the token this builds
        // could carry the stale placeholder auth0Sub instead of the real one just set above.
        verify(authService).issueSessionFor(captor.getValue());
    }

    @Test
    void activateAccount_withoutAuth0Provider_stillActivatesLocally() {
        userService = userService(Optional.empty());
        User pending = User.builder()
                .id(9L).email("nuevo.analista@arbiter.test").auth0Sub("pending:tok-123")
                .inviteToken("tok-123").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-123")).thenReturn(Optional.of(pending));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authService.issueSessionFor(any(User.class))).thenReturn(dummySession());

        userService.activateAccount("tok-123", "NuevaPass123!");

        verify(userRepository).save(pending);
        assertThat(pending.isActivated()).isTrue();
        assertThat(pending.getAuth0Sub()).isEqualTo("pending:tok-123");
    }

    @Test
    void activateAccount_invalidToken_throwsInvalidInviteToken() {
        userService = userService(Optional.empty());
        when(userRepository.findByInviteToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activateAccount("bad-token", "NuevaPass123!"))
                .isInstanceOf(InvalidInviteTokenException.class);
        verifyNoInteractions(authService);
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
        verifyNoInteractions(authService);
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
        // Failing before the save means no session for a half-activated account either.
        verifyNoInteractions(authService);
    }

    @Test
    void requestPasswordReset_existingEmail_generatesTokenAndSendsMail() {
        userService = userService(Optional.empty());
        User existing = User.builder().id(3L).email("analista.test@arbiter.test").build();
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
    void resetPassword_validToken_updatesAuth0AndClearsToken() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User active = User.builder()
                .id(9L).email("analista.test@arbiter.test")
                .inviteToken("tok-456").inviteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(userRepository.findByInviteToken("tok-456")).thenReturn(Optional.of(active));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        LoginResponse session = dummySession();
        when(authService.issueSessionFor(any(User.class))).thenReturn(session);

        LoginResponse result = userService.resetPassword("tok-456", "OtraPass456!");

        assertThat(result).isSameAs(session);
        verify(auth0UserProvisioner).updatePassword("analista.test@arbiter.test", "OtraPass456!");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getInviteToken()).isNull();
        assertThat(captor.getValue().getInviteExpiresAt()).isNull();
        verify(authService).issueSessionFor(captor.getValue());
    }

    @Test
    void resetPassword_invalidToken_throwsInvalidInviteToken() {
        userService = userService(Optional.empty());
        when(userRepository.findByInviteToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword("bad-token", "OtraPass456!"))
                .isInstanceOf(InvalidInviteTokenException.class);
        verifyNoInteractions(authService);
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
        verifyNoInteractions(authService);
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
                .id(11L).email("pendiente.test@arbiter.test")
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
        User active = User.builder().id(7L).email("analista.test@arbiter.test").activated(true).build();
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
    void listUsers_scopesToCallersOwnInsurer() {
        userService = userService(Optional.empty());
        stubCaller();
        User own = User.builder().id(7L).email("propio@arbiter.test").createdAt(Instant.now()).build();
        when(userInsurerRepository.findByInsurerId(CALLER_INSURER_ID))
                .thenReturn(java.util.List.of(UserInsurer.builder().user(own).insurerId(CALLER_INSURER_ID).build()));
        when(userRepository.findAllById(java.util.List.of(7L))).thenReturn(java.util.List.of(own));

        var response = userService.listUsers("referente@arbiter.test");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(7L);
    }

    @Test
    void updateRole_validTarget_changesRole() {
        userService = userService(Optional.empty());
        User target = User.builder().id(7L).email("analista.test@arbiter.test")
                .roles(new HashSet<>(Set.of(analystRole()))).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        Role referenteRole = Role.builder().id(3L).code("REFERENTE_ASEGURADORA").name("Referente de aseguradora").build();
        when(roleRepository.findByCode("REFERENTE_ASEGURADORA")).thenReturn(Optional.of(referenteRole));
        lenient().when(tenantProfileService.find(any(), any())).thenReturn(Optional.empty());

        UserResponse response = userService.updateRole(7L, UserRole.REFERENTE_ASEGURADORA, "referente@arbiter.test");

        assertThat(response.rol()).isEqualTo(UserRole.REFERENTE_ASEGURADORA);
        assertThat(target.getRoles()).extracting(Role::getCode).containsExactly("REFERENTE_ASEGURADORA");
    }

    @Test
    void updateRole_ownAccount_throwsCannotChangeOwnRole() {
        userService = userService(Optional.empty());
        User self = User.builder().id(3L).email("referente@arbiter.test").build();
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
        User target = User.builder().id(7L).email("analista.test@arbiter.test").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        userService.deleteUser(7L, "referente@arbiter.test");

        verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_ownAccount_throwsCannotDeleteOwnAccount() {
        userService = userService(Optional.empty());
        User self = User.builder().id(3L).email("referente@arbiter.test").build();
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
        User target = User.builder().id(7L).email("analista.test@arbiter.test").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        userService.deleteUser(7L, "referente@arbiter.test");

        verify(auth0UserProvisioner).deleteUser("analista.test@arbiter.test");
        verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_auth0DeletionFails_doesNotDeleteLocalUser() {
        userService = userService(Optional.of(auth0UserProvisioner));
        User target = User.builder().id(7L).email("analista.test@arbiter.test").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        Auth0ProvisioningException failure =
                new Auth0ProvisioningException("borrar", "analista.test@arbiter.test", new RuntimeException("Auth0 down"));
        doThrow(failure).when(auth0UserProvisioner).deleteUser(any());

        assertThatThrownBy(() -> userService.deleteUser(7L, "referente@arbiter.test"))
                .isInstanceOf(Auth0ProvisioningException.class);

        verify(userRepository, never()).delete(any());
    }
}
