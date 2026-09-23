package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InsuredProfileNotFoundException;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsuredProfileServiceTest {

    @Mock
    private InsuredRepository insuredRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private TenantResolver tenantResolver;

    @InjectMocks
    private InsuredProfileService service;

    @Test
    void unknownEmail_isUserNotFound() {
        when(userRepository.findByEmail("nadie@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile("nadie@example.com"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void userWithoutInsuredProfile_isProfileNotFound() {
        User user = User.builder().email("alguien@example.com").build();
        user.setId(3L);
        when(userRepository.findByEmail("alguien@example.com")).thenReturn(Optional.of(user));
        when(insuredRepository.findByUserId(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile("alguien@example.com"))
                .isInstanceOf(InsuredProfileNotFoundException.class);
    }

    @Test
    void knownEmail_returnsTheInsuredProfile() {
        User user = User.builder().email("alguien@example.com").build();
        user.setId(3L);
        Insured insured = Insured.builder().dni("42.987.654").name("Laura").user(user).build();
        when(userRepository.findByEmail("alguien@example.com")).thenReturn(Optional.of(user));
        when(insuredRepository.findByUserId(3L)).thenReturn(Optional.of(insured));

        assertThat(service.getProfile("alguien@example.com").dni()).isEqualTo("42.987.654");
    }
}
