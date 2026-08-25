package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import ar.edu.utn.frba.arbiter.auth.models.repositories.RoleRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bulk "dar de alta usuarios" run. What matters here is that it never splits one person's
 * identity in two: the same human insured at two companies is one login, and re-running the alta
 * has to be a no-op rather than a second account.
 */
@ExtendWith(MockitoExtension.class)
class InsuredAccountProvisionerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserInsurerRepository userInsurerRepository;

    @Mock
    private TenantProfileService tenantProfileService;

    @InjectMocks
    private InsuredAccountProvisioner provisioner;

    private static InsuredDirectoryEntry entry() {
        return new InsuredDirectoryEntry(
                "33.845.219", "Roman", "Castillo", "roman@example.com", "11-5555-0007");
    }

    private static User existingUser(Long id) {
        User user = User.builder().email("roman@example.com").auth0Sub("auth0|real").build();
        user.setId(id);
        return user;
    }

    private void roleExists() {
        when(roleRepository.findByCode(UserRole.ASEGURADO.name()))
                .thenReturn(Optional.of(Role.builder().code(UserRole.ASEGURADO.name()).build()));
    }

    @Test
    void unknownEmail_createsPendingUserAndMintsAnInviteToken() {
        when(userRepository.findByEmail("roman@example.com")).thenReturn(Optional.empty());
        roleExists();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            user.setId(9L);
            return user;
        });
        when(userInsurerRepository.findByUserId(9L)).thenReturn(List.of());

        InsuredAccountProvisioner.Outcome outcome = provisioner.provisionOne(entry(), 1L);

        assertThat(outcome.userCreated()).isTrue();
        assertThat(outcome.inviteToken()).isNotBlank();
        // Nobody exists in Auth0 until they choose a password — the sub is a placeholder until then.
        assertThat(outcome.user().getAuth0Sub()).startsWith("pending:");
        assertThat(outcome.user().isActivated()).isFalse();
    }

    /**
     * The case the whole design turns on: Roman is insured at BBVA and at Provincia. Provisioning
     * the second company must reuse his account and only add the membership.
     */
    @Test
    void knownEmail_reusesTheAccountAndOnlyLinksTheInsurer() {
        when(userRepository.findByEmail("roman@example.com")).thenReturn(Optional.of(existingUser(9L)));
        when(userInsurerRepository.findByUserId(9L))
                .thenReturn(List.of(UserInsurer.builder().insurerId(1L).build()));

        InsuredAccountProvisioner.Outcome outcome = provisioner.provisionOne(entry(), 2L);

        assertThat(outcome.userCreated()).isFalse();
        assertThat(outcome.insurerLinked()).isTrue();
        // No second identity, and no unsolicited mail to someone who already has an account.
        verify(userRepository, never()).save(any(User.class));
        assertThat(outcome.inviteToken()).isNull();
        verify(userInsurerRepository).save(any(UserInsurer.class));
    }

    @Test
    void alreadyLinkedInsurer_isANoOp() {
        when(userRepository.findByEmail("roman@example.com")).thenReturn(Optional.of(existingUser(9L)));
        when(userInsurerRepository.findByUserId(9L))
                .thenReturn(List.of(UserInsurer.builder().insurerId(1L).build()));

        InsuredAccountProvisioner.Outcome outcome = provisioner.provisionOne(entry(), 1L);

        assertThat(outcome.userCreated()).isFalse();
        assertThat(outcome.insurerLinked()).isFalse();
        verify(userInsurerRepository, never()).save(any(UserInsurer.class));
    }

    @Test
    void profileCreationIsDelegatedAndReported() {
        when(userRepository.findByEmail("roman@example.com")).thenReturn(Optional.of(existingUser(9L)));
        when(userInsurerRepository.findByUserId(9L)).thenReturn(List.of());
        when(tenantProfileService.createInsuredIfMissing(
                any(User.class), anyString(), anyString(), eq("33.845.219"), anyString(), anyString()))
                .thenReturn(true);

        InsuredAccountProvisioner.Outcome outcome = provisioner.provisionOne(entry(), 2L);

        assertThat(outcome.profileCreated()).isTrue();
    }
}
