package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.rules.exceptions.RuleAuthorNotFoundException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.UserRepository;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerReferentRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Resolving the actor's email to the referente id stored in the history's changed_by. */
class RuleAuthorResolverTest {

    private final InsurerReferentRepository insurerReferentRepository = mock(InsurerReferentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RuleAuthorResolver resolver = new RuleAuthorResolver(insurerReferentRepository, userRepository);

    @Test
    void resolvesTheReferenteOfTheActor() {
        when(insurerReferentRepository.findFirstByUser_Email("referente@bbva.com"))
                .thenReturn(Optional.of(InsurerReferent.builder().id(11L).build()));

        assertThat(resolver.referentIdOf("referente@bbva.com")).isEqualTo(11L);
    }

    /** An actor with no referente profile leaves the author empty instead of failing the save. */
    @Test
    void isNullWhenTheActorHasNoReferenteProfile() {
        when(insurerReferentRepository.findFirstByUser_Email("analista@bbva.com")).thenReturn(Optional.empty());

        assertThat(resolver.referentIdOf("analista@bbva.com")).isNull();
    }

    @Test
    void isNullWithoutAnActor() {
        assertThat(resolver.referentIdOf(null)).isNull();
        assertThat(resolver.referentIdOf(" ")).isNull();
        verifyNoInteractions(insurerReferentRepository);
    }

    @Test
    void resolvesTheCreatorAsAUser() {
        when(userRepository.findByEmail("referente@bbva.com"))
                .thenReturn(Optional.of(User.builder().id(3L).build()));

        assertThat(resolver.userIdOf("referente@bbva.com")).isEqualTo(3L);
    }

    /** Unlike a change's author, a creation's is mandatory: without it the rule isn't saved. */
    @Test
    void refusesToCreateARuleWithoutAKnownUser() {
        when(userRepository.findByEmail("nadie@bbva.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.userIdOf("nadie@bbva.com"))
                .isInstanceOf(RuleAuthorNotFoundException.class);
        assertThatThrownBy(() -> resolver.userIdOf(null))
                .isInstanceOf(RuleAuthorNotFoundException.class);
    }
}
