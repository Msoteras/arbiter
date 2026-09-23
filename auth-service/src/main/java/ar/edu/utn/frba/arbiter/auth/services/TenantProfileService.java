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
 * Per-tenant profile tables for each role (insured / claims_analyst / insurer_referent). Every
 * method needs {@code TenantContext} already pointed at the right schema.
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
     * Keyed by document. A no-op when the row already exists for this same user, since it may carry
     * consent and onboarding state the insurer's directory must not overwrite.
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

    public void createClaimsAnalyst(User user, String name, String surname, String email) {
        claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .user(user)
                .name(name)
                .surname(surname)
                .email(email)
                .build());
    }

    /** The profile FKs to {@code users} aren't {@code ON DELETE CASCADE}: call this before deleting the user. */
    public void deleteProfile(UserRole role, Long userId) {
        switch (role) {
            case ASEGURADO -> insuredRepository.findByUserId(userId).ifPresent(insuredRepository::delete);
            case ANALISTA_SINIESTROS -> claimsAnalystRepository.findByUserId(userId).ifPresent(claimsAnalystRepository::delete);
            case REFERENTE_ASEGURADORA -> insurerReferentRepository.findByUserId(userId).ifPresent(insurerReferentRepository::delete);
        }
    }
}
