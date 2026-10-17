package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationDto;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The branch's derivation-to-expert policy. "Not configured" is a business answer, not a gap: it's
 * opt-in because below some amount an expert assessment costs more than the claim.
 */
class ExpertDerivationServiceTest {

    private static final Long BRANCH_ID = 1L;
    private static final String ACTOR = "referente@aseguradora.com";

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository =
            mock(InsurerRuleHistoryRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final RuleAuthorResolver authorResolver = mock(RuleAuthorResolver.class);
    private final ExpertDerivationService service = new ExpertDerivationService(
            ruleRepository, new BranchRuleWriter(ruleRepository, historyRepository, branchRepository, authorResolver));

    @Test
    void readsTheThresholdOffTheRuleConfiguration() {
        givenRule(rule(true, "{\"minClaimedAmount\":500000}"));

        ExpertDerivationDto policy = service.getByBranch(BRANCH_ID);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.minClaimedAmount()).isEqualByComparingTo("500000");
        assertThat(policy.ruleId()).isEqualTo(4L);
    }

    /** An insurer that never loaded the rule doesn't derive: opt-in, not a default. */
    @Test
    void isDisabledWhenTheInsurerHasNoRuleForTheBranch() {
        givenNoRule();

        assertThat(service.getByBranch(BRANCH_ID).enabled()).isFalse();
    }

    /** Deactivating the rule stops derivations without deleting the history. */
    @Test
    void isDisabledWhenTheRuleIsInactive() {
        givenRule(rule(false, "{\"minClaimedAmount\":500000}"));

        assertThat(service.getByBranch(BRANCH_ID).enabled()).isFalse();
    }

    /** An active rule with no amount is a misconfiguration, not "derive everything". */
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

    /** The panel has to show the threshold of a rule that is off, or re-enabling it loses the number. */
    @Test
    void theReferentSeesTheAmountOfAnInactiveRule() {
        givenRule(rule(false, "{\"minClaimedAmount\":500000}"));

        ExpertDerivationRuleDto rule = service.getRule(BRANCH_ID);

        assertThat(rule.enabled()).isFalse();
        assertThat(rule.minClaimedAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void theReferentSeesAnUnconfiguredBranchAsOff() {
        givenNoRule();

        assertThat(service.getRule(BRANCH_ID)).isEqualTo(ExpertDerivationRuleDto.unconfigured());
    }

    @Test
    void createsTheRuleTheFirstTimeTheBranchIsConfigured() {
        givenNoRule();
        when(branchRepository.findById(BRANCH_ID)).thenReturn(Optional.of(new Branch()));
        when(authorResolver.userIdOf(ACTOR)).thenReturn(7L);

        service.upsert(BRANCH_ID, new ExpertDerivationRuleDto(true, new BigDecimal("800000")), ACTOR);

        ArgumentCaptor<InsurerRule> saved = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getRuleType()).isEqualTo(ExpertDerivationService.EXPERT_DERIVATION);
        assertThat(saved.getValue().getCoverageId()).isNull();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(7L);
        assertThat(saved.getValue().getConfiguration()).isEqualTo("{\"minClaimedAmount\":800000}");
        verify(historyRepository, never()).save(any());
    }

    /** Opt-in: saving a branch that never derived, still off, must not leave a row behind. */
    @Test
    void savingAnUnconfiguredBranchAsOffCreatesNothing() {
        givenNoRule();

        service.upsert(BRANCH_ID, ExpertDerivationRuleDto.unconfigured(), ACTOR);

        verify(ruleRepository, never()).save(any());
    }

    @Test
    void rejectsEnablingWithoutAnAmount() {
        givenNoRule();

        assertThatThrownBy(() ->
                service.upsert(BRANCH_ID, new ExpertDerivationRuleDto(true, null), ACTOR))
                .isInstanceOf(InvalidRuleConfigurationException.class);
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void changingTheAmountSnapshotsThePreviousVersion() {
        InsurerRule existing = rule(true, "{\"minClaimedAmount\":500000}");
        givenRule(existing);

        service.upsert(BRANCH_ID, new ExpertDerivationRuleDto(true, new BigDecimal("800000")), ACTOR);

        ArgumentCaptor<InsurerRuleHistory> history = ArgumentCaptor.forClass(InsurerRuleHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getConfigVersion()).contains("500000");
        assertThat(existing.getConfiguration()).isEqualTo("{\"minClaimedAmount\":800000}");
    }

    @Test
    void turningTheRuleOffKeepsTheStoredAmount() {
        InsurerRule existing = rule(true, "{\"minClaimedAmount\":500000}");
        givenRule(existing);

        ExpertDerivationRuleDto saved =
                service.upsert(BRANCH_ID, new ExpertDerivationRuleDto(false, null), ACTOR);

        assertThat(existing.isActive()).isFalse();
        assertThat(saved.minClaimedAmount()).isEqualByComparingTo("500000");
        verify(historyRepository).save(any());
    }

    /** The panel sends amounts with decimals; the same number must not read as a change. */
    @Test
    void savingTheSameAmountLeavesNoAuditEntry() {
        givenRule(rule(true, "{\"minClaimedAmount\":500000}"));

        service.upsert(BRANCH_ID, new ExpertDerivationRuleDto(true, new BigDecimal("500000.00")), ACTOR);

        verify(historyRepository, never()).save(any());
        verify(ruleRepository, never()).save(any());
    }

    private void givenRule(InsurerRule rule) {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, ExpertDerivationService.EXPERT_DERIVATION))
                .thenReturn(Optional.of(rule));
    }

    private void givenNoRule() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, ExpertDerivationService.EXPERT_DERIVATION))
                .thenReturn(Optional.empty());
    }

    private InsurerRule rule(boolean active, String configuration) {
        return InsurerRule.builder()
                .id(4L)
                .active(active)
                .name("Derivar a peritaje")
                .ruleType(ExpertDerivationService.EXPERT_DERIVATION)
                .effect("DERIVAR")
                .configuration(configuration)
                .build();
    }
}
