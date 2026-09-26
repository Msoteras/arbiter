package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.AnalystResponse;
import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotChangeOwnRoleException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotDeleteOwnAccountException;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidInviteTokenException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InviteTokenExpiredException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserAlreadyActiveException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final long INVITE_VALIDITY_HOURS = 48;
    private static final long RESET_VALIDITY_HOURS = 2;

    private final UserRepository userRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final RoleRepository roleRepository;
    private final UserInsurerRepository userInsurerRepository;
    private final TenantResolver tenantResolver;
    private final TenantProfileService tenantProfileService;
    private final Optional<Auth0UserProvisioner> auth0UserProvisioner;
    private final EmailDomainValidator emailDomainValidator;
    private final SendGridAdapter sendGridAdapter;
    private final PasswordCipher passwordCipher;
    private final InsuredProvisioningService insuredProvisioningService;
    private final AuthService authService;

    @Value("${arbiter.frontend.base-url:http://localhost:4200}")
    private String frontendBaseUrl;

    /**
     * Leaves the analyst "pending" with a 48h invite token; they're created in Auth0 only on
     * {@link #activateAccount}. {@code auth0Sub} is NOT NULL, so it holds a placeholder until then.
     * The user joins the caller's insurer.
     *
     * <p>Transactional so a failure halfway doesn't leave a half-created account whose email can't
     * be retried.
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request, String callerEmail) {
        if (request.rol() != UserRole.ANALISTA_SINIESTROS) {
            throw new RoleNotAllowedException(request.rol());
        }
        emailDomainValidator.validate(request.email());
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no encontrado: " + callerEmail));
        Long insurerId = tenantResolver.insurerIdsFor(caller.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Referente sin aseguradora asignada: " + callerEmail));

        String inviteToken = UUID.randomUUID().toString();
        User user = User.builder()
                .email(request.email())
                .auth0Sub("pending:" + inviteToken)
                .inviteToken(inviteToken)
                .inviteExpiresAt(Instant.now().plus(INVITE_VALIDITY_HOURS, ChronoUnit.HOURS))
                .build();

        Role analystRole = roleRepository.findByCode(UserRole.ANALISTA_SINIESTROS.name())
                .orElseThrow(() -> new IllegalStateException("Falta el rol ANALISTA_SINIESTROS en el catálogo"));
        user.setRoles(new HashSet<>(List.of(analystRole)));

        User saved = userRepository.save(user);
        userInsurerRepository.save(UserInsurer.builder().user(saved).insurerId(insurerId).build());
        tenantProfileService.createClaimsAnalyst(saved, request.nombre(), request.apellido(), request.email());

        try {
            sendGridAdapter.send(request.email(), "Activá tu cuenta en Arbiter",
                    invitationEmailBody(request.nombre(), inviteToken));
        } catch (RuntimeException e) {
            userRepository.delete(saved);
            throw e;
        }

        return toResponse(saved, request.nombre(), request.apellido(), UserRole.ANALISTA_SINIESTROS);
    }

    /**
     * The tenant is read here, on the request thread, because {@link TenantContext} won't survive
     * the async hand-off. The insurer comes from the caller's membership, never from the request.
     */
    public void provisionInsuredAccounts(String callerEmail) {
        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no encontrado: " + callerEmail));
        Long insurerId = tenantResolver.insurerIdsFor(caller.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Referente sin aseguradora asignada: " + callerEmail));

        insuredProvisioningService.provisionAsync(TenantContext.get(), insurerId);
    }

    /**
     * Creates the user in Auth0 first; if that fails nothing local changes, so the same link can
     * be retried. Returns a started session.
     *
     * <p>Deliberately NOT {@code @Transactional}: {@code issueSessionFor} switches
     * {@link TenantContext} mid-flow, and a shared Hibernate session would stay pinned to the schema
     * of its first query.
     */
    public LoginResponse activateAccount(String token, String encryptedPassword) {
        String rawPassword = passwordCipher.decrypt(encryptedPassword);
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            String auth0Sub = auth0UserProvisioner.get().createUser(user.getEmail(), rawPassword);
            user.setAuth0Sub(auth0Sub);
        }

        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        user.setActivated(true);
        User saved = userRepository.save(user);
        log.info("[Auth] Cuenta activada — userId={} email={}", saved.getId(), saved.getEmail());
        return authService.issueSessionFor(saved);
    }

    /**
     * Behaves the same whether or not the email exists, logs included, so it doesn't leak which
     * addresses are registered.
     */
    public void requestPasswordReset(String email) {
        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            log.info("[Auth] Password reset requested for an unregistered email");
            return;
        }
        User user = found.get();
        user.setInviteToken(UUID.randomUUID().toString());
        user.setInviteExpiresAt(Instant.now().plus(RESET_VALIDITY_HOURS, ChronoUnit.HOURS));
        userRepository.save(user);
        sendGridAdapter.send(user.getEmail(), "Restablecé tu contraseña en Arbiter",
                resetEmailBody(greetingFor(user), user.getInviteToken()));
        log.info("[Auth] Reset email sent: userId={} email={}", user.getId(), user.getEmail());
    }

    /**
     * First name if it can be resolved, the email otherwise. The request is anonymous, so the
     * tenant has to be resolved here.
     */
    private String greetingFor(User user) {
        // Null-safe: a User built without .roles(...) has a null set (no @Builder.Default).
        Optional<UserRole> rol = user.getRoles() == null
                ? Optional.empty()
                : user.getRoles().stream().findFirst().map(Role::getCode).map(UserRole::valueOf);
        Optional<Insurer> primaryInsurer = rol.isPresent()
                ? tenantResolver.primaryInsurerFor(user.getId())
                : Optional.empty();
        if (primaryInsurer.isEmpty()) {
            return user.getEmail();
        }
        TenantContext.set(primaryInsurer.get().getSchemaName());
        try {
            return tenantProfileService.find(rol.get(), user.getId())
                    .map(TenantProfileService.Profile::name)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(user.getEmail());
        } finally {
            TenantContext.clear();
        }
    }

    /** Not {@code @Transactional}, for the same tenant-switch reason as {@link #activateAccount}. */
    public LoginResponse resetPassword(String token, String encryptedPassword) {
        String rawPassword = passwordCipher.decrypt(encryptedPassword);
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            auth0UserProvisioner.get().updatePassword(user.getEmail(), rawPassword);
        }

        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        User saved = userRepository.save(user);
        log.info("[Auth] Password reset: userId={} email={}", saved.getId(), saved.getEmail());
        return authService.issueSessionFor(saved);
    }

    /** Doesn't consume the token. */
    public void checkToken(String token) {
        requireValidToken(token);
    }

    public UserResponse resendInvite(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.isActivated()) {
            throw new UserAlreadyActiveException();
        }

        user.setInviteToken(UUID.randomUUID().toString());
        user.setInviteExpiresAt(Instant.now().plus(INVITE_VALIDITY_HOURS, ChronoUnit.HOURS));
        User saved = userRepository.save(user);

        UserResponse response = toResponse(saved);
        sendGridAdapter.send(user.getEmail(), "Activá tu cuenta en Arbiter",
                invitationEmailBody(response.nombre(), user.getInviteToken()));

        return response;
    }

    private User requireValidToken(String token) {
        User user = userRepository.findByInviteToken(token).orElseThrow(InvalidInviteTokenException::new);
        if (user.getInviteExpiresAt() == null || user.getInviteExpiresAt().isBefore(Instant.now())) {
            throw new InviteTokenExpiredException();
        }
        return user;
    }

    private String invitationEmailBody(String nombre, String token) {
        String activationUrl = linkFor("/activate-account", token);
        return """
                <p>Hola %s,</p>
                <p>Te invitaron a sumarte a Arbiter. Hacé clic en el siguiente link para elegir tu
                contraseña y activar tu cuenta:</p>
                <p><a href="%s">%s</a></p>
                <p>Este link vence en 48 horas.</p>
                """.formatted(nombre, activationUrl, activationUrl);
    }

    private String resetEmailBody(String greeting, String token) {
        String resetUrl = linkFor("/reset-password", token);
        return """
                <p>Hola %s,</p>
                <p>Pediste restablecer tu contraseña en Arbiter. Hacé clic en el siguiente link
                para elegir una nueva:</p>
                <p><a href="%s">%s</a></p>
                <p>Si no fuiste vos, ignorá este mail — tu contraseña actual sigue siendo válida.</p>
                <p>Este link vence en %d horas.</p>
                """.formatted(greeting, resetUrl, resetUrl, RESET_VALIDITY_HOURS);
    }

    private String linkFor(String path, String token) {
        return frontendBaseUrl + path + "?token=" + token;
    }

    /** Narrowed to the caller's insurer: {@code users} is common to every tenant. */
    public List<UserResponse> listUsers(String callerEmail) {
        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no encontrado: " + callerEmail));
        Long insurerId = tenantResolver.insurerIdsFor(caller.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Referente sin aseguradora asignada: " + callerEmail));

        List<Long> userIds = userInsurerRepository.findByInsurerId(insurerId).stream()
                .map(ui -> ui.getUser().getId())
                .toList();

        return userRepository.findAllById(userIds).stream()
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    /** {@code claims_analyst} is per-schema, so the resolved tenant already narrows it to one insurer. */
    public List<AnalystResponse> listAssignableAnalysts() {
        return claimsAnalystRepository.findAllByOrderBySurnameAscNameAsc().stream()
                .map(a -> new AnalystResponse(a.getId(), a.getName(), a.getSurname(), a.getEmail()))
                .toList();
    }

    /** The old role's profile row is NOT migrated to the new role. */
    public UserResponse updateRole(Long userId, UserRole newRole, String requestingEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotChangeOwnRoleException();
        }

        Role role = roleRepository.findByCode(newRole.name())
                .orElseThrow(() -> new IllegalStateException("Rol no encontrado en el catálogo: " + newRole));
        user.setRoles(new HashSet<>(List.of(role)));
        return toResponse(userRepository.save(user));
    }

    public void deleteUser(Long userId, String requestingEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotDeleteOwnAccountException();
        }

        auth0UserProvisioner.ifPresent(provisioner -> provisioner.deleteUser(user.getEmail()));

        // The profile FK isn't ON DELETE CASCADE. The caller's tenant is the target's too.
        user.getRoles().stream().findFirst().map(Role::getCode).map(UserRole::valueOf)
                .ifPresent(rol -> tenantProfileService.deleteProfile(rol, user.getId()));
        userInsurerRepository.deleteAll(userInsurerRepository.findByUserId(user.getId()));

        userRepository.delete(user);
    }

    /** Assumes TenantContext already points at this user's schema. */
    private UserResponse toResponse(User user) {
        UserRole rol = user.getRoles().stream()
                .findFirst()
                .map(Role::getCode)
                .map(UserRole::valueOf)
                .orElse(null);
        var profile = rol != null ? tenantProfileService.find(rol, user.getId()) : Optional.<TenantProfileService.Profile>empty();
        return toResponse(user, profile.map(TenantProfileService.Profile::name).orElse(null),
                profile.map(TenantProfileService.Profile::surname).orElse(null), rol);
    }

    private UserResponse toResponse(User user, String nombre, String apellido, UserRole rol) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                nombre,
                apellido,
                rol,
                user.isActivated() ? UserStatus.ACTIVE : UserStatus.PENDING,
                user.getCreatedAt());
    }
}
