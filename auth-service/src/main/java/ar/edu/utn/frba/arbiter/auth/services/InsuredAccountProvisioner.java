package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import ar.edu.utn.frba.arbiter.auth.models.repositories.RoleRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provisions <b>one</b> policyholder's platform account, idempotently.
 *
 * <p>Separate from {@link InsuredProvisioningService} so each person gets their own transaction:
 * in a bulk of thousands, one bad row must skip that person and not roll back the batch. Calling
 * it from the loop crosses the proxy boundary, which is what actually gives each iteration its
 * own commit.
 */
@Component
@RequiredArgsConstructor
public class InsuredAccountProvisioner {

    private static final long INVITE_VALIDITY_HOURS = 48;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserInsurerRepository userInsurerRepository;
    private final TenantProfileService tenantProfileService;

    /**
     * @param inviteToken the token to mail, or null when this person needs no invitation (they
     *                    already had an account — re-inviting them would be unsolicited mail, and
     *                    {@code UserService.resendInvite} already covers the one-off case)
     */
    public record Outcome(
            User user,
            boolean userCreated,
            boolean insurerLinked,
            boolean profileCreated,
            String inviteToken) {
    }

    /**
     * Matches on <b>email</b>, never on document: the same person insured at two companies is one
     * login with two {@code user_insurer} rows — Roman Castillo is exactly that in the fixtures.
     * Keying on (insurer, DNI) would give them a second account and split their identity in half.
     *
     * <p>Note {@code users.email} carries no UNIQUE constraint yet (the schema flags it as
     * pending), so this lookup is the only thing standing between a re-run and a duplicate
     * identity. The migration that ships with this flow adds the index; until it is applied, do
     * not run two of these concurrently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome provisionOne(InsuredDirectoryEntry entry, Long insurerId) {
        Optional<User> existing = userRepository.findByEmail(entry.email());

        User user = existing.orElseGet(() -> createUser(entry));
        boolean userCreated = existing.isEmpty();

        boolean insurerLinked = linkInsurer(user, insurerId);
        boolean profileCreated = createProfileIfMissing(user, entry);

        return new Outcome(user, userCreated, insurerLinked, profileCreated,
                userCreated ? user.getInviteToken() : null);
    }

    /**
     * Created "pending", exactly like the analyst invitation: {@code auth0_sub} is NOT NULL, so it
     * holds a placeholder derived from the invite token until activation replaces it with the real
     * Auth0 subject. Nobody exists in Auth0 until they choose a password.
     */
    private User createUser(InsuredDirectoryEntry entry) {
        String inviteToken = UUID.randomUUID().toString();
        User user = User.builder()
                .email(entry.email())
                .auth0Sub("pending:" + inviteToken)
                .inviteToken(inviteToken)
                .inviteExpiresAt(Instant.now().plus(INVITE_VALIDITY_HOURS, ChronoUnit.HOURS))
                .build();

        Role insuredRole = roleRepository.findByCode(UserRole.ASEGURADO.name())
                .orElseThrow(() -> new IllegalStateException("Falta el rol ASEGURADO en el catálogo"));
        user.setRoles(new HashSet<>(List.of(insuredRole)));

        return userRepository.save(user);
    }

    /**
     * The membership row is what actually hands an existing user this insurer's policies: the
     * portal aggregates them live off the signed {@code insurerIds} claim, which is built from this
     * table. So for someone already on the platform, linking <i>is</i> the "append their policies"
     * step — nothing gets copied into {@code arbiter_*.policy}.
     */
    private boolean linkInsurer(User user, Long insurerId) {
        boolean alreadyLinked = userInsurerRepository.findByUserId(user.getId()).stream()
                .anyMatch(link -> insurerId.equals(link.getInsurerId()));
        if (alreadyLinked) {
            return false;
        }
        userInsurerRepository.save(UserInsurer.builder().user(user).insurerId(insurerId).build());
        return true;
    }

    private boolean createProfileIfMissing(User user, InsuredDirectoryEntry entry) {
        return tenantProfileService.createInsuredIfMissing(
                user, entry.name(), entry.surname(), entry.dni(), entry.email(), entry.phone());
    }
}
