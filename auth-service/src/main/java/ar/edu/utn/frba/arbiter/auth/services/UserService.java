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
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleChangeNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserAlreadyActiveException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserHasActivityException;
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
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final long INVITE_VALIDITY_HOURS = 48;
    private static final long RESET_VALIDITY_HOURS = 2;

    /** The only roles a referent may swap between; insureds come from the insurer's directory. */
    private static final Set<UserRole> STAFF_ROLES =
            Set.of(UserRole.ANALISTA_SINIESTROS, UserRole.REFERENTE_ASEGURADORA);

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
    private final RulesServiceClient rulesServiceClient;
    private final TransactionTemplate transactionTemplate;

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

        Long insurerId = callerInsurerId(callerEmail);

        String inviteToken = UUID.randomUUID().toString();
        User user = User.builder()
                .email(request.email())
                .auth0Sub("pending:" + inviteToken)
                .inviteToken(inviteToken)
                .inviteExpiresAt(Instant.now().plus(INVITE_VALIDITY_HOURS, ChronoUnit.HOURS))
                .build();

        Role analystRole = roleRepository.findByCode(UserRole.ANALISTA_SINIESTROS.name())
                .orElseThrow(() -> new IllegalStateException("Role ANALISTA_SINIESTROS missing from the catalog"));
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

        rulesServiceClient.recordOnboardingSave(OnboardingStep.ANALYSTS);
        return toResponse(saved, request.nombre(), request.apellido(), UserRole.ANALISTA_SINIESTROS);
    }

    /**
     * The tenant is read here, on the request thread, because {@link TenantContext} won't survive
     * the async hand-off. The insurer comes from the caller's membership, never from the request.
     */
    public void provisionInsuredAccounts(String callerEmail) {
        Long insurerId = callerInsurerId(callerEmail);

        insuredProvisioningService.provisionAsync(TenantContext.get(), insurerId);
        rulesServiceClient.recordOnboardingSave(OnboardingStep.INSURED);
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

    public UserResponse resendInvite(Long userId, String callerEmail) {
        User user = requireMember(userId, callerInsurerId(callerEmail));

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
        Long insurerId = callerInsurerId(callerEmail);

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
        return claimsAnalystRepository.findCurrentAnalysts().stream()
                .map(a -> new AnalystResponse(a.getId(), a.getName(), a.getSurname(), a.getEmail()))
                .toList();
    }

    /**
     * Only swaps between analyst and referent. The new role's profile is created in the same
     * transaction; the old one stays, since cases may already point at it.
     */
    @Transactional
    public UserResponse updateRole(Long userId, UserRole newRole, String requestingEmail) {
        User user = requireMember(userId, callerInsurerId(requestingEmail));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotChangeOwnRoleException();
        }

        UserRole currentRole = roleOf(user).orElse(null);
        if (!STAFF_ROLES.contains(currentRole) || !STAFF_ROLES.contains(newRole)) {
            throw new RoleChangeNotAllowedException();
        }
        if (newRole == currentRole) {
            return toResponse(user);
        }

        TenantProfileService.Profile profile = tenantProfileService.find(currentRole, user.getId())
                .orElseThrow(() -> new IllegalStateException("Staff user without a profile: " + userId));
        tenantProfileService.createStaffProfileIfMissing(newRole, user, profile.name(), profile.surname());

        Role role = roleRepository.findByCode(newRole.name())
                .orElseThrow(() -> new IllegalStateException("Role not found in the catalog: " + newRole));
        user.setRoles(new HashSet<>(List.of(role)));
        return toResponse(userRepository.save(user));
    }

    /**
     * Removes the user from the caller's insurer. With no other insurer left, the account goes too:
     * first everything in the database, in one transaction, and Auth0 last, so a database failure
     * never leaves someone locked out with their data still in place.
     *
     * @throws UserHasActivityException if recorded activity still points at the user; nothing changes
     */
    public void deleteUser(Long userId, String requestingEmail) {
        Long insurerId = callerInsurerId(requestingEmail);
        User user = requireMember(userId, insurerId);

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotDeleteOwnAccountException();
        }

        boolean accountRemoved = Boolean.TRUE.equals(
                transactionTemplate.execute(status -> removeFromInsurer(user, insurerId)));

        if (accountRemoved) {
            auth0UserProvisioner.ifPresent(provisioner -> deleteFromAuth0(provisioner, user));
        }
    }

    /**
     * The foreign keys decide what counts as activity: every table that records something a user
     * did points at {@code users} or at their profile, so the delete fails on any of them.
     *
     * @return whether the account itself was deleted, as opposed to only unlinked from this insurer
     */
    private boolean removeFromInsurer(User user, Long insurerId) {
        try {
            // The profile lives in the caller's schema, which is the resolved tenant.
            roleOf(user).ifPresent(rol -> tenantProfileService.deleteProfile(rol, user.getId()));

            List<UserInsurer> memberships = userInsurerRepository.findByUserId(user.getId());
            memberships.stream()
                    .filter(m -> m.getInsurerId().equals(insurerId))
                    .forEach(userInsurerRepository::delete);

            boolean lastMembership = memberships.size() == 1;
            if (lastMembership) {
                userRepository.delete(user);
            }
            userRepository.flush();
            return lastMembership;
        } catch (DataIntegrityViolationException e) {
            throw new UserHasActivityException(e);
        }
    }

    /**
     * Runs after the commit, so the account is already gone from Arbiter: a failure here leaves an
     * orphan in Auth0 that can no longer log in, logged for manual cleanup.
     */
    private void deleteFromAuth0(Auth0UserProvisioner provisioner, User user) {
        try {
            provisioner.deleteUser(user.getEmail());
        } catch (RuntimeException e) {
            log.error("[Auth] User deleted locally but not in Auth0: userId={} email={}",
                    user.getId(), user.getEmail(), e);
        }
    }

    private Long callerInsurerId(String callerEmail) {
        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + callerEmail));
        return tenantResolver.insurerIdsFor(caller.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Referent without an assigned insurer: " + callerEmail));
    }

    /**
     * {@code users} is common to every insurer, so an id alone could reach another insurer's account.
     * A user outside the caller's insurer answers the same as one that doesn't exist.
     */
    private User requireMember(Long userId, Long insurerId) {
        if (userInsurerRepository.findByUserIdAndInsurerId(userId, insurerId).isEmpty()) {
            throw new UserNotFoundException(userId);
        }
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    private Optional<UserRole> roleOf(User user) {
        return user.getRoles().stream().findFirst().map(Role::getCode).map(UserRole::valueOf);
    }

    /** Assumes TenantContext already points at this user's schema. */
    private UserResponse toResponse(User user) {
        UserRole rol = roleOf(user).orElse(null);
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
                user.getCreatedAt(),
                user.getLastAccessAt());
    }
}
