package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotChangeOwnRoleException;
import ar.edu.utn.frba.arbiter.auth.exceptions.CannotDeleteOwnAccountException;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Optional<Auth0UserProvisioner> auth0UserProvisioner;
    private final EmailDomainValidator emailDomainValidator;

    public UserResponse createUser(CreateUserRequest request) {
        if (request.rol() != UserRole.ANALISTA_SINIESTROS) {
            throw new RoleNotAllowedException(request.rol());
        }
        emailDomainValidator.validate(request.email());
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .nombre(request.nombre())
                .apellido(request.apellido())
                .rol(request.rol())
                .sector(request.sector())
                .fechaIngreso(request.fechaIngreso())
                .build();

        User saved = userRepository.save(user);

        if (auth0UserProvisioner.isPresent()) {
            try {
                auth0UserProvisioner.get().createUser(request.email(), request.password());
            } catch (RuntimeException e) {
                userRepository.delete(saved);
                throw e;
            }
        }

        return toResponse(saved);
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
