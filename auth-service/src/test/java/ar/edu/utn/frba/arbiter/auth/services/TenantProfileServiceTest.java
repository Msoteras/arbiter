package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InsuredProfileConflictException;
import ar.edu.utn.frba.arbiter.auth.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerReferentRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The insured profile side of the bulk alta. What is being pinned here is that a document already
 * on the platform is only "nothing to do" when it belongs to the <b>same</b> person — anything else
 * has to stop, not pass silently.
 */
@ExtendWith(MockitoExtension.class)
class TenantProfileServiceTest {

    @Mock
    private InsuredRepository insuredRepository;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Mock
    private InsurerReferentRepository insurerReferentRepository;

    @InjectMocks
    private TenantProfileService service;

    private static User user(Long id) {
        User user = User.builder().email("alguien@example.com").build();
        user.setId(id);
        return user;
    }

    private static Insured insuredOf(User owner) {
        return Insured.builder().dni("42.987.654").user(owner).build();
    }

    @Test
    void unknownDni_createsTheProfile() {
        when(insuredRepository.findByDni("42.987.654")).thenReturn(Optional.empty());

        boolean created = service.createInsuredIfMissing(
                user(1L), "Martina", "Soteras", "42.987.654", "m@example.com", "11-5555-0001");

        assertThat(created).isTrue();
        verify(insuredRepository).save(any(Insured.class));
    }

    @Test
    void dniAlreadyOwnedByTheSameUser_isLeftAlone() {
        when(insuredRepository.findByDni("42.987.654")).thenReturn(Optional.of(insuredOf(user(1L))));

        boolean created = service.createInsuredIfMissing(
                user(1L), "Martina", "Soteras", "42.987.654", "m@example.com", "11-5555-0001");

        assertThat(created).isFalse();
        // Consent and onboarding state live on that row — the company's directory must not overwrite them.
        verify(insuredRepository, never()).save(any(Insured.class));
    }

    /**
     * The shape the real data already has: the insurer lists a policyholder under an address that on
     * the platform belongs to a different account. Reporting that as "already provisioned" would
     * pair her with somebody else's login.
     */
    @Test
    void dniOwnedByAnotherUser_failsInsteadOfPassingSilently() {
        when(insuredRepository.findByDni("42.987.654")).thenReturn(Optional.of(insuredOf(user(1L))));

        assertThatThrownBy(() -> service.createInsuredIfMissing(
                user(7L), "Martina", "Soteras", "42.987.654", "m@example.com", "11-5555-0001"))
                .isInstanceOf(InsuredProfileConflictException.class)
                .hasMessageContaining("42.987.654");

        verify(insuredRepository, never()).save(any(Insured.class));
    }
}
