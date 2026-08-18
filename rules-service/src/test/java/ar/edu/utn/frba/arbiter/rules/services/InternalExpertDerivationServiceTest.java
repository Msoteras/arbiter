package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La política de derivación a peritaje del ramo. "Sin configurar" es una respuesta del negocio y
 * no un hueco: el peritaje es opt-in porque abajo de cierto monto cuesta más que el siniestro.
 */
class InternalExpertDerivationServiceTest {

    private static final Long BRANCH_ID = 1L;

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InternalExpertDerivationService service =
            new InternalExpertDerivationService(ruleRepository);

    @Test
    void readsTheThresholdOffTheRuleConfiguration() {
        givenRule(rule(true, "{\"minClaimedAmount\":500000}"));

        ExpertDerivationDto policy = service.getByBranch(BRANCH_ID);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.minClaimedAmount()).isEqualByComparingTo("500000");
        assertThat(policy.ruleId()).isEqualTo(4L);
    }

    /** Una aseguradora que nunca cargó la regla no deriva: es opt-in, no un default. */
    @Test
    void isDisabledWhenTheInsurerHasNoRuleForTheBranch() {
        givenNoRule();

        assertThat(service.getByBranch(BRANCH_ID).enabled()).isFalse();
    }

    /** Desactivar la regla es cómo el referente corta las derivaciones sin borrar el historial. */
    @Test
    void isDisabledWhenTheRuleIsInactive() {
        givenRule(rule(false, "{\"minClaimedAmount\":500000}"));

        assertThat(service.getByBranch(BRANCH_ID).enabled()).isFalse();
    }

    /**
     * Una regla activa sin monto es una configuración rota, no "derivá todo": leerla como cero
     * habilitaría el peritaje para cada siniestro del ramo sin que nadie lo haya pedido.
     */
    @Test
    void failsOnAnActiveRuleWithNoThreshold() {
        givenRule(rule(true, "{}"));

        assertThatThrownBy(() -> service.getByBranch(BRANCH_ID))
                .isInstanceOf(InvalidRuleConfigurationException.class);
    }

    @Test
    void failsOnUnreadableConfiguration() {
        givenRule(rule(true, "no es json"));

        assertThatThrownBy(() -> service.getByBranch(BRANCH_ID))
                .isInstanceOf(InvalidRuleConfigurationException.class);
    }

    private void givenRule(InsurerRule rule) {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, InternalExpertDerivationService.EXPERT_DERIVATION))
                .thenReturn(Optional.of(rule));
    }

    private void givenNoRule() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, InternalExpertDerivationService.EXPERT_DERIVATION))
                .thenReturn(Optional.empty());
    }

    private InsurerRule rule(boolean active, String configuration) {
        return InsurerRule.builder()
                .id(4L)
                .active(active)
                .name("Derivar a peritaje")
                .ruleType(InternalExpertDerivationService.EXPERT_DERIVATION)
                .effect("DERIVAR")
                .configuration(configuration)
                .build();
    }
}
