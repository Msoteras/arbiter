package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationDto;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepairDerivationServiceTest {

    private static final Long BRANCH_ID = 1L;
    private static final String ACTOR = "referente@aseguradora.com";

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository =
            mock(InsurerRuleHistoryRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);
    private final RuleAuthorResolver authorResolver = mock(RuleAuthorResolver.class);
    private final RepairDerivationService service = new RepairDerivationService(
            ruleRepository, claimCauseRepository,
            new BranchRuleWriter(ruleRepository, historyRepository, branchRepository, authorResolver));

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

    @Test
    void theReferentSeesTheClaimCausesOfAnInactiveRule() {
        givenRule(rule(false, "{\"claimCauseIds\":[1,4]}"));

        RepairDerivationRuleDto rule = service.getRule(BRANCH_ID);

        assertThat(rule.enabled()).isFalse();
        assertThat(rule.claimCauseIds()).containsExactly(1L, 4L);
    }

    @Test
    void createsTheRuleTheFirstTimeTheBranchIsConfigured() {
        givenNoRule();
        givenClaimCausesOfBranch(1L, 4L);
        when(branchRepository.findById(BRANCH_ID)).thenReturn(Optional.of(new Branch()));
        when(authorResolver.userIdOf(ACTOR)).thenReturn(7L);

        service.upsert(BRANCH_ID, new RepairDerivationRuleDto(true, List.of(4L, 1L, 4L)), ACTOR);

        ArgumentCaptor<InsurerRule> saved = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getRuleType()).isEqualTo(RepairDerivationService.REPAIR_DERIVATION);
        assertThat(saved.getValue().getConfiguration()).isEqualTo("{\"claimCauseIds\":[1,4]}");
    }

    @Test
    void rejectsEnablingWithoutClaimCauses() {
        givenNoRule();

        assertThatThrownBy(() ->
                service.upsert(BRANCH_ID, new RepairDerivationRuleDto(true, List.of()), ACTOR))
                .isInstanceOf(InvalidRuleConfigurationException.class);
        verify(ruleRepository, never()).save(any());
    }

    /** Ids repeat across branches: one from another branch would silently never match a claim. */
    @Test
    void rejectsAClaimCauseOfAnotherBranch() {
        givenNoRule();
        givenClaimCausesOfBranch(1L, 4L);

        assertThatThrownBy(() ->
                service.upsert(BRANCH_ID, new RepairDerivationRuleDto(true, List.of(1L, 6L)), ACTOR))
                .isInstanceOf(InvalidRuleConfigurationException.class);
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void turningTheRuleOffKeepsTheStoredClaimCauses() {
        InsurerRule existing = rule(true, "{\"claimCauseIds\":[1,4]}");
        givenRule(existing);

        RepairDerivationRuleDto saved =
                service.upsert(BRANCH_ID, new RepairDerivationRuleDto(false, List.of()), ACTOR);

        assertThat(existing.isActive()).isFalse();
        assertThat(saved.claimCauseIds()).containsExactly(1L, 4L);
        verify(historyRepository).save(any());
    }

    @Test
    void savingTheSameSelectionLeavesNoAuditEntry() {
        givenRule(rule(true, "{\"claimCauseIds\":[1,4]}"));
        givenClaimCausesOfBranch(1L, 4L);

        service.upsert(BRANCH_ID, new RepairDerivationRuleDto(true, List.of(4L, 1L)), ACTOR);

        verify(historyRepository, never()).save(any());
        verify(ruleRepository, never()).save(any());
    }

    private void givenClaimCausesOfBranch(Long... ids) {
        List<ClaimCause> causes = Arrays.stream(ids)
                .map(id -> {
                    ClaimCause cause = mock(ClaimCause.class);
                    when(cause.getId()).thenReturn(id);
                    return cause;
                })
                .toList();
        when(claimCauseRepository.findByBranch_IdOrderByNameAsc(BRANCH_ID)).thenReturn(causes);
    }

    private void givenRule(InsurerRule rule) {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, RepairDerivationService.REPAIR_DERIVATION))
                .thenReturn(Optional.of(rule));
    }

    private void givenNoRule() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(
                BRANCH_ID, RepairDerivationService.REPAIR_DERIVATION))
                .thenReturn(Optional.empty());
    }

    private InsurerRule rule(boolean active, String configuration) {
        return InsurerRule.builder()
                .id(9L)
                .active(active)
                .name("Derivar a reparación")
                .ruleType(RepairDerivationService.REPAIR_DERIVATION)
                .effect("DERIVAR")
                .configuration(configuration)
                .build();
    }
}
