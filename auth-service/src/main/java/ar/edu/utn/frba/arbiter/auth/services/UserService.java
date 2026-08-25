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
     * The referente no longer sets a password (Auth0 owns it end to end): the user is left
     * "pending" with a one-time invite token (48h) and gets an email to choose their own
     * password — see {@link #activateAccount}, which is where they actually get created in
     * Auth0. {@code auth0Sub} is NOT NULL on {@code users}, so it gets a placeholder derived
     * from the invite token (unique, same as the token itself) until activation overwrites it
     * with the real Auth0 subject. The new user is linked to the SAME insurer as whoever is
     * inviting them ({@code callerEmail}, the authenticated referente) — this endpoint only
     * ever creates ANALISTA_SINIESTROS, so their tenant profile row is a {@code claims_analyst}
     * in the caller's own schema, already the active one for this request
     * (TenantResolvingFilter set it from the caller's own JWT).
     */
    // Without this, a failure halfway through (say the claims_analyst insert) leaves `users` and
    // `user_insurer` already committed separately: the email gets "stuck" on a half-created account
    // that neither activates nor can be retried (it hits EmailAlreadyExistsException).
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
     * "Dar de alta usuarios": kicks off the bulk provisioning of the referente's own insured, from
     * the company's directory. Returns as soon as the run is dispatched — the work happens off the
     * request thread.
     *
     * <p>The tenant is read <b>here</b>, on the request thread, because {@link TenantContext} is a
     * {@code ThreadLocal} that will not survive the hand-off. The insurer comes from the caller's
     * own membership, never from the request, so a referente can only ever provision their own book.
     */
    public void provisionInsuredAccounts(String callerEmail) {
        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no encontrado: " + callerEmail));
        Long insurerId = tenantResolver.insurerIdsFor(caller.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Referente sin aseguradora asignada: " + callerEmail));

        insuredProvisioningService.provisionAsync(TenantContext.get(), insurerId);
    }

    /**
     * The invited user lands here from the email link. Only at this point do we create them
     * in Auth0 (with the password they chose) — if Auth0 fails, we don't touch anything local,
     * so the user can retry with the same link without the referente having to re-invite them.
     *
     * <p>Returns an already-issued session so the frontend can start it straight away instead of
     * bouncing the person to a login screen for the password they just chose — see
     * {@link AuthService#issueSessionFor}.
     */
    @Transactional
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
        return authService.issueSessionFor(saved);
    }

    /**
     * "Forgot my password": if the email exists, generates a new token (reusing the same invite
     * columns) and sends the link. Responds the same way whether or not the email exists — no
     * leaking which addresses are registered in the system. No authenticated tenant to read a
     * display name from at this point, so the email greets by address instead of by name.
     */
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            user.setInviteToken(UUID.randomUUID().toString());
            user.setInviteExpiresAt(Instant.now().plus(RESET_VALIDITY_HOURS, ChronoUnit.HOURS));
            userRepository.save(user);
            sendGridAdapter.send(user.getEmail(), "Restablecé tu contraseña en Arbiter",
                    resetEmailBody(user.getEmail(), user.getInviteToken()));
        });
    }

    /**
     * The user already exists in Auth0 (unlike {@link #activateAccount}) — this only updates
     * the password there, then clears the local token. Same reasoning as activation for
     * returning a session instead of nothing: whoever just reset their password already proved
     * they own the mailbox and chose the new one, so there's nothing left for a login screen to
     * ask them.
     */
    @Transactional
    public LoginResponse resetPassword(String token, String encryptedPassword) {
        String rawPassword = passwordCipher.decrypt(encryptedPassword);
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            auth0UserProvisioner.get().updatePassword(user.getEmail(), rawPassword);
        }

        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        User saved = userRepository.save(user);
        return authService.issueSessionFor(saved);
    }

    /**
     * Read-only validation — doesn't consume the token. The frontend calls this before showing
     * the password form, so a made-up or expired token in the URL never gets to see that screen.
     */
    public void checkToken(String token) {
        requireValidToken(token);
    }

    /**
     * The referente sends a fresh invite to a user who never activated their account (expired
     * link, or they just never got to it). Generates a new token with the same 48h validity —
     * there's no cron cleaning up expired invites, this is the only way to unstick them.
     */
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

    /**
     * H0003 (Trello) - users with their current role, narrowed to the insurer of the referente
     * asking for the list (before multi-tenancy this returned EVERY user in the system regardless
     * of insurer — a real isolation hole, not an intentional simplification).
     */
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

    /**
     * Analysts a case can be assigned to, for the bandeja's picker. An analyst can ask for it too,
     * not just the referente: assigning is an action of both operational roles.
     *
     * <p>Comes from {@code claims_analyst} and not {@code users}: name and surname live there, and
     * being a per-schema table it's already narrowed to the request's insurer with no extra filter
     * (decision #10). The id it returns is the one {@code cases.analyst_id} expects.
     */
    public List<AnalystResponse> listAssignableAnalysts() {
        return claimsAnalystRepository.findAllByOrderBySurnameAscNameAsc().stream()
                .map(a -> new AnalystResponse(a.getId(), a.getName(), a.getSurname(), a.getEmail()))
                .toList();
    }

    /**
     * Changes a user's role. The referente can promote another referente (not a real escalation:
     * they already have full access), but not change their own — it keeps them from demoting or
     * locking themselves out by accident. The profile row of the old role
     * (claims_analyst/insured/insurer_referent) is NOT migrated to the new one: changing roles is
     * an edge case with no real flow behind it today.
     */
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

    /**
     * Deletes a user for good (wireframe "Eliminar", irreversible — not a deactivation). The
     * referente can't delete themselves, same reason as {@link #updateRole}: it keeps them from
     * losing access.
     */
    public void deleteUser(Long userId, String requestingEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotDeleteOwnAccountException();
        }

        auth0UserProvisioner.ifPresent(provisioner -> provisioner.deleteUser(user.getEmail()));

        // The profile row's FK to users isn't ON DELETE CASCADE, so it has to go first. Runs
        // under the caller's own tenant (already active for this request) — safe because a
        // referente only ever manages users in their own insurer, same one as the target.
        user.getRoles().stream().findFirst().map(Role::getCode).map(UserRole::valueOf)
                .ifPresent(rol -> tenantProfileService.deleteProfile(rol, user.getId()));
        userInsurerRepository.deleteAll(userInsurerRepository.findByUserId(user.getId()));

        userRepository.delete(user);
    }

    /** Assumes TenantContext is already pointed at the right schema for this user (true for
     * every caller here — either the request's own tenant, authenticated via
     * TenantResolvingFilter, or freshly created in the same tenant a moment earlier). */
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
