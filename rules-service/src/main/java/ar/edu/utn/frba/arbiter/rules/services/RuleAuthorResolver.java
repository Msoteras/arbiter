package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.rules.exceptions.RuleAuthorNotFoundException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuleAuthorResolver {

    private final InsurerReferentRepository insurerReferentRepository;
    private final UserRepository userRepository;

    /** Null without a referente profile in this tenant: a missing author must not block the save. */
    public Long referentIdOf(String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            return null;
        }
        return insurerReferentRepository.findFirstByUser_Email(actorEmail)
                .map(InsurerReferent::getId)
                .orElse(null);
    }

    /** Required, unlike {@link #referentIdOf}: a rule can't be created without its author. */
    public Long userIdOf(String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new RuleAuthorNotFoundException(actorEmail);
        }
        return userRepository.findByEmail(actorEmail)
                .map(User::getId)
                .orElseThrow(() -> new RuleAuthorNotFoundException(actorEmail));
    }
}
