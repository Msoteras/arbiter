package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerReferentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns the actor of a rule change (the JWT carries their email) into the {@code insurer_referent}
 * id that the history tables' {@code changed_by} points at.
 */
@Component
@RequiredArgsConstructor
public class RuleAuthorResolver {

    private final InsurerReferentRepository insurerReferentRepository;

    /**
     * Null when the actor has no referente profile in this tenant: a missing author must not keep
     * the rule from being saved.
     */
    public Long referentIdOf(String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            return null;
        }
        return insurerReferentRepository.findFirstByUser_Email(actorEmail)
                .map(InsurerReferent::getId)
                .orElse(null);
    }
}
