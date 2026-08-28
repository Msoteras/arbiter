package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InsuredProfileConflictException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads/writes the per-tenant profile table for a user's role (insured / claims_analyst
 * / insurer_referent) — name and last name moved there when {@link User} lost its own
 * columns for them. Every method here has to run with
 * {@link ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext} already pointed at
 * the right schema; none of this touches the common schema.
 */
@Component
@RequiredArgsConstructor
public class TenantProfileService {

    public record Profile(String name, String surname, String dni, Boolean onboardingComplete) {}

    private final InsuredRepository insuredRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final InsurerReferentRepository insurerReferentRepository;

    public Optional<Profile> find(UserRole role, Long userId) {
        return switch (role) {
            case ASEGURADO -> insuredRepository.findByUserId(userId)
                    .map(i -> new Profile(i.getName(), i.getSurname(), i.getDni(), i.isOnboardingComplete()));
            case ANALISTA_SINIESTROS -> claimsAnalystRepository.findByUserId(userId)
                    .map(c -> new Profile(c.getName(), c.getSurname(), null, null));
            case REFERENTE_ASEGURADORA -> insurerReferentRepository.findByUserId(userId)
                    .map(r -> new Profile(r.getName(), r.getSurname(), null, null));
        };
    }

    /**
     * The insured's tenant profile, created by the bulk "dar de alta usuarios" run from the
     * company's own directory.
     *
     * <p>Keyed by document, and a no-op when the row is already there <b>for this same user</b>: by
     * then it may carry consent and onboarding state the person set themselves, which the insurer's
     * directory knows nothing about and must not overwrite.
     *
     * <p>A row under a different user is a different story — see
     * {@link InsuredProfileConflictException}. Treating it as "nothing to do" would report the
     * person as already provisioned while pairing them with somebody else's login.
     *
     * @return whether it created the row
     * @throws InsuredProfileConflictException if the document already belongs to another user
     */
    public boolean createInsuredIfMissing(
            User user, String name, String surname, String dni, String email, String phone) {
        Optional<Insured> existing = insuredRepository.findByDni(dni);
        if (existing.isPresent()) {
            Long ownerId = existing.get().getUser().getId();
            if (!ownerId.equals(user.getId())) {
                throw new InsuredProfileConflictException(dni, ownerId, user.getId());
            }
            return false;
        }
        insuredRepository.save(Insured.builder()
                .name(name)
                .surname(surname)
                .dni(dni)
                .email(email)
                .phone(phone)
                .user(user)
                .build());
        return true;
    }

    /** Only ANALISTA_SINIESTROS gets created through the Usuarios panel today (decision #8). */
    public void createClaimsAnalyst(User user, String name, String surname, String email) {
        claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .user(user)
                .name(name)
                .surname(surname)
                .email(email)
                .build());
    }

    /**
     * The profile FKs to {@code users} aren't {@code ON DELETE CASCADE} — deleting the
     * user without this first fails with a constraint violation.
     */
    public void deleteProfile(UserRole role, Long userId) {
        switch (role) {
            case ASEGURADO -> insuredRepository.findByUserId(userId).ifPresent(insuredRepository::delete);
            case ANALISTA_SINIESTROS -> claimsAnalystRepository.findByUserId(userId).ifPresent(claimsAnalystRepository::delete);
            case REFERENTE_ASEGURADORA -> insurerReferentRepository.findByUserId(userId).ifPresent(insurerReferentRepository::delete);
        }
    }
}
