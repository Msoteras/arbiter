package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.models.entities.ClaimsAnalyst;
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

    public record Profile(String name, String surname, String dni) {}

    private final InsuredRepository insuredRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final InsurerReferentRepository insurerReferentRepository;

    public Optional<Profile> find(UserRole role, Long userId) {
        return switch (role) {
            case ASEGURADO -> insuredRepository.findByUserId(userId)
                    .map(i -> new Profile(i.getName(), i.getSurname(), i.getDni()));
            case ANALISTA_SINIESTROS -> claimsAnalystRepository.findByUserId(userId)
                    .map(c -> new Profile(c.getName(), c.getSurname(), null));
            case REFERENTE_ASEGURADORA -> insurerReferentRepository.findByUserId(userId)
                    .map(r -> new Profile(r.getName(), r.getSurname(), null));
        };
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
