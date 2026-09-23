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
 * Provisions one policyholder's account, idempotently. A separate bean from
 * {@link InsuredProvisioningService} so the call crosses the proxy and each person gets their own
 * transaction: one bad row mustn't roll back the batch.
 */
@Component
@RequiredArgsConstructor
public class InsuredAccountProvisioner {

    private static final long INVITE_VALIDITY_HOURS = 48;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserInsurerRepository userInsurerRepository;
    private final TenantProfileService tenantProfileService;

    /** @param inviteToken null when the person already had an account and needs no invitation */
    public record Outcome(
            User user,
            boolean userCreated,
            boolean insurerLinked,
            boolean profileCreated,
            String inviteToken) {
    }

    /**
     * Matches on <b>email</b>, never on document: the same person insured at two companies is one
     * login with two {@code user_insurer} rows.
     *
     * <p>Without the unique index on {@code users.email} this lookup is the only duplicate guard,
     * so don't run two provisioning runs concurrently.
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
     * {@code auth0_sub} is NOT NULL, so it holds a placeholder until activation sets the real Auth0
     * subject. Nobody exists in Auth0 until they choose a password.
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
     * Linking is what gives an existing user this insurer's policies: the portal reads them off the
     * {@code insurerIds} claim, built from this table. Nothing gets copied.
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
