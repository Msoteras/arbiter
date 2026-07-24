package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotChangeOwnRoleException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotDeleteOwnAccountException;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidInviteTokenException;
import ar.edu.utn.frba.arbiter.auth.exceptions.InviteTokenExpiredException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
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
     * El referente ya no fija contraseña (Fase 3 Auth0): el usuario queda "pendiente" con un
     * token de invitación de un solo uso (48hs) y recibe un mail para elegir su propia
     * contraseña — ver {@link #activateAccount}, que es donde recién se lo crea en Auth0.
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
     * El usuario invitado llega acá desde el link del mail. Recién en este momento se lo crea
     * en Auth0 (con la contraseña que eligió) — si Auth0 falla, no tocamos nada local, así el
     * usuario puede reintentar con el mismo link sin que el referente tenga que darlo de alta
     * de nuevo.
     */
    public void activateAccount(String token, String rawPassword) {
        User user = requireValidToken(token);

        if (auth0UserProvisioner.isPresent()) {
            auth0UserProvisioner.get().createUser(user.getEmail(), rawPassword);
        }

        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setInviteToken(null);
        user.setInviteExpiresAt(null);
        userRepository.save(user);
    }

    /**
     * "Olvidé mi contraseña": si el email existe, le genera un token nuevo (reusa las mismas
     * columnas de la invitación) y le manda el link. Responde igual exista o no el email — no
     * hay que filtrar qué direcciones están registradas en el sistema.
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
     * El usuario ya existe en Auth0 (a diferencia de {@link #activateAccount}) — acá solo se le
     * actualiza la contraseña, misma lógica de "Auth0 primero, commit local después".
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
     * Solo valida — no consume el token. La usa el frontend antes de mostrar el formulario de
     * contraseña, para no dejar ver esa pantalla con un token inventado o vencido en la URL.
     */
    public void checkToken(String token) {
        requireValidToken(token);
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
                user.getCreatedAt());
    }
}
