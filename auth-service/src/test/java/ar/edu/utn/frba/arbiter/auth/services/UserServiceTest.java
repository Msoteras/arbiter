package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.EmailAlreadyExistsException;
import ar.edu.utn.frba.arbiter.auth.exceptions.RoleNotAllowedException;
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
}
