package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotChangeOwnRoleException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotDeleteOwnAccountException;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long INVITE_VALIDITY_HOURS = 48;
    private static final long RESET_VALIDITY_HOURS = 2;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Optional<Auth0UserProvisioner> auth0UserProvisioner;
    private final EmailDomainValidator emailDomainValidator;
    private final SendGridAdapter sendGridAdapter;

    @Value("${arbiter.frontend.base-url:http://localhost:4200}")
    private String frontendBaseUrl;

    /**
     * The referente no longer sets a password (Auth0 Phase 3): the user is left "pending" with
     * a one-time invite token (48h) and gets an email to choose their own password — see
     * {@link #activateAccount}, which is where they actually get created in Auth0.
     */
    public UserResponse createUser(CreateUserRequest request) {
        if (request.rol() != UserRole.ANALISTA_SINIESTROS) {
            throw new RoleNotAllowedException(request.rol());
        }
        emailDomainValidator.validate(request.email());
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException(request.email());
        }

        String inviteToken = UUID.randomUUID().toString();
        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .nombre(request.nombre())
                .apellido(request.apellido())
                .rol(request.rol())
                .sector(request.sector())
                .fechaIngreso(request.fechaIngreso())
                .inviteToken(inviteToken)
                .inviteExpiresAt(Instant.now().plus(INVITE_VALIDITY_HOURS, ChronoUnit.HOURS))
                .build();

        User saved = userRepository.save(user);

        try {
            sendGridAdapter.send(request.email(), "Activá tu cuenta en Arbiter",
                    invitationEmailBody(request.nombre(), inviteToken));
        } catch (RuntimeException e) {
            userRepository.delete(saved);
            throw e;
        }

        return toResponse(saved);
    }

    /**
     * The invited user lands here from the email link. Only at this point do we create them
     * in Auth0 (with the password they chose) — if Auth0 fails, we don't touch anything local,
     * so the user can retry with the same link without the referente having to re-invite them.
     */
    public void activateAccount(String token, String rawPassword) {
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            auth0UserProvisioner.get().createUser(user.getEmail(), rawPassword);
        }

        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        user.setActivated(true);
        userRepository.save(user);
    }

    /**
     * "Forgot my password": if the email exists, generates a new token (reusing the same invite
     * columns) and sends the link. Responds the same way whether or not the email exists — no
     * leaking which addresses are registered in the system.
     */
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            user.setInviteToken(UUID.randomUUID().toString());
            user.setInviteExpiresAt(Instant.now().plus(RESET_VALIDITY_HOURS, ChronoUnit.HOURS));
            userRepository.save(user);
            sendGridAdapter.send(user.getEmail(), "Restablecé tu contraseña en Arbiter",
                    resetEmailBody(user.getNombre(), user.getInviteToken()));
        });
    }

    /**
     * The user already exists in Auth0 (unlike {@link #activateAccount}) — this only updates
     * the password, same "Auth0 first, local commit after" logic.
     */
    public void resetPassword(String token, String rawPassword) {
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            auth0UserProvisioner.get().updatePassword(user.getEmail(), rawPassword);
        }

        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        userRepository.save(user);
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

        sendGridAdapter.send(user.getEmail(), "Activá tu cuenta en Arbiter",
                invitationEmailBody(user.getNombre(), user.getInviteToken()));

        return toResponse(saved);
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

    private String resetEmailBody(String nombre, String token) {
        String resetUrl = linkFor("/reset-password", token);
        return """
                <p>Hola %s,</p>
                <p>Pediste restablecer tu contraseña en Arbiter. Hacé clic en el siguiente link
                para elegir una nueva:</p>
                <p><a href="%s">%s</a></p>
                <p>Si no fuiste vos, ignorá este mail — tu contraseña actual sigue siendo válida.</p>
                <p>Este link vence en %d horas.</p>
                """.formatted(nombre, resetUrl, resetUrl, RESET_VALIDITY_HOURS);
    }

    private String linkFor(String path, String token) {
        return frontendBaseUrl + path + "?token=" + token;
    }

    /** H0003 (Trello) - listado de usuarios con su rol actual. */
    public List<UserResponse> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Analistas a los que se les puede asignar un expediente. Es un recorte del listado completo
     * de usuarios pensado para el selector de asignación de la bandeja: solo el rol
     * ANALISTA_SINIESTROS, y por eso también lo puede consultar un analista (no solo el referente).
     *
     * <p>Devuelve también los que están PENDING (invitados sin activar): quien asigna ve el estado
     * y decide. Hoy no filtra por aseguradora — ese recorte llega con el esquema multi-tenant
     * (decisión de arquitectura #10, ver GAPS-FLUJO.md Gap F).
     */
    public List<UserResponse> listAssignableAnalysts() {
        return userRepository.findByRolOrderByApellidoAscNombreAsc(UserRole.ANALISTA_SINIESTROS).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Cambia el rol de un usuario. El referente puede promover a otro referente (no es una
     * escalada real: ya tiene acceso completo), pero no puede cambiarse el rol a sí mismo —
     * evita que se autodegrade o se bloquee sin querer.
     */
    public UserResponse updateRole(Long userId, UserRole newRole, String requestingEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotChangeOwnRoleException();
        }

        user.setRol(newRole);
        return toResponse(userRepository.save(user));
    }

    /**
     * Elimina un usuario definitivamente (wireframe "Eliminar", acción irreversible — no es una
     * baja/desactivación). El referente no puede eliminarse a sí mismo, mismo motivo que
     * {@link #updateRole}: evita quedarse sin acceso.
     */
    public void deleteUser(Long userId, String requestingEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail().equals(requestingEmail)) {
            throw new CannotDeleteOwnAccountException();
        }

        auth0UserProvisioner.ifPresent(provisioner -> provisioner.deleteUser(user.getEmail()));
        userRepository.delete(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNombre(),
                user.getApellido(),
                user.getRol(),
                user.getSector(),
                user.getFechaIngreso(),
                user.isActivated() ? UserStatus.ACTIVE : UserStatus.PENDING,
                user.getCreatedAt());
    }
}
