package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalRepairDerivationServiceTest {

    private static final Long BRANCH_ID = 1L;

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InternalRepairDerivationService service =
            new InternalRepairDerivationService(ruleRepository);

    @Test
    void readsTheClaimCausesOffTheRuleConfiguration() {
        givenRule(rule(true, "{\"claimCauseIds\":[1,4]}"));

        RepairDerivationDto policy = service.getByBranch(BRANCH_ID);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.claimCauseIds()).containsExactly(1L, 4L);
        assertThat(policy.ruleId()).isEqualTo(9L);
    }

    /** Opt-in, like peritaje: an insurer that never loaded the rule does not derive to repair. */
    @Test
    void isDisabledWhenTheInsurerHasNoRuleForTheBranch() {
        givenNoRule();

        RepairDerivationDto policy = service.getByBranch(BRANCH_ID);

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.claimCauseIds()).isEmpty();
    }

    @Test
    void isDisabledWhenTheRuleIsInactive() {
        givenRule(rule(false, "{\"claimCauseIds\":[1,4]}"));

        assertThat(service.getByBranch(BRANCH_ID).enabled()).isFalse();
    }

    @Test
    void failsOnAnActiveRuleWithNoClaimCauses() {
        givenRule(rule(true, "{\"claimCauseIds\":[]}"));

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
                BRANCH_ID, InternalRepairDerivationService.REPAIR_DERIVATION))
                .thenReturn(Optional.of(rule));
    }

    private void givenNoRule() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, InternalRepairDerivationService.REPAIR_DERIVATION))
                .thenReturn(Optional.empty());
    }

    private InsurerRule rule(boolean active, String configuration) {
        return InsurerRule.builder()
                .id(9L)
                .active(active)
                .name("Derivar a reparación")
                .ruleType(InternalRepairDerivationService.REPAIR_DERIVATION)
                .effect("DERIVAR")
                .configuration(configuration)
                .build();
    }
}
