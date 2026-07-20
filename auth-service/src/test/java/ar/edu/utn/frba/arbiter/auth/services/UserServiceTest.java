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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    private CreateUserRequest analistaRequest() {
        return new CreateUserRequest(
                "nuevo.analista@arbiter.test", "Lucas", "Gómez", "changeme123",
                UserRole.ANALISTA_SINIESTROS, "Siniestros Celulares", LocalDate.of(2026, 1, 1));
    }

    @Test
    void createUser_validRequest_hashesPasswordAndPersists() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.findByEmail("nuevo.analista@arbiter.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("changeme123")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserResponse response = userService.createUser(analistaRequest());

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("nuevo.analista@arbiter.test");
        assertThat(response.rol()).isEqualTo(UserRole.ANALISTA_SINIESTROS);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-value");
    }

    @Test
    void createUser_duplicateEmail_throwsEmailAlreadyExists() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.findByEmail("nuevo.analista@arbiter.test"))
                .thenReturn(Optional.of(User.builder().id(1L).build()));

        assertThatThrownBy(() -> userService.createUser(analistaRequest()))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void createUser_roleOtherThanAnalista_throwsRoleNotAllowed() {
        userService = new UserService(userRepository, passwordEncoder);
        CreateUserRequest request = new CreateUserRequest(
                "asegurado@arbiter.test", "Martina", "Fernández", "changeme123",
                UserRole.ASEGURADO, "N/A", null);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void updateRole_validTarget_changesRole() {
        userService = new UserService(userRepository, passwordEncoder);
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.updateRole(7L, UserRole.REFERENTE_ASEGURADORA, "referente@arbiter.test");

        assertThat(response.rol()).isEqualTo(UserRole.REFERENTE_ASEGURADORA);
    }

    @Test
    void updateRole_ownAccount_throwsCannotChangeOwnRole() {
        userService = new UserService(userRepository, passwordEncoder);
        User self = User.builder().id(3L).email("referente@arbiter.test").rol(UserRole.REFERENTE_ASEGURADORA).build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> userService.updateRole(3L, UserRole.ANALISTA_SINIESTROS, "referente@arbiter.test"))
                .isInstanceOf(CannotChangeOwnRoleException.class);
    }

    @Test
    void updateRole_unknownUser_throwsUserNotFound() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateRole(999L, UserRole.ASEGURADO, "referente@arbiter.test"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deleteUser_validTarget_deletesIt() {
        userService = new UserService(userRepository, passwordEncoder);
        User target = User.builder().id(7L).email("analista.test@arbiter.test").rol(UserRole.ANALISTA_SINIESTROS).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        userService.deleteUser(7L, "referente@arbiter.test");

        verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_ownAccount_throwsCannotDeleteOwnAccount() {
        userService = new UserService(userRepository, passwordEncoder);
        User self = User.builder().id(3L).email("referente@arbiter.test").rol(UserRole.REFERENTE_ASEGURADORA).build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> userService.deleteUser(3L, "referente@arbiter.test"))
                .isInstanceOf(CannotDeleteOwnAccountException.class);
    }

    @Test
    void deleteUser_unknownUser_throwsUserNotFound() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(999L, "referente@arbiter.test"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
