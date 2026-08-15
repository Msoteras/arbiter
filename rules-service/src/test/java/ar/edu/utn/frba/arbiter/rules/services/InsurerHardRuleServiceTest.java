package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The referente's Hard Stop rules (coverage window, arrears), scoped to the whole insurer instead
 * of a coverage: the full catalog on get, the arrears-only {@code onArrears} choice, and the
 * upsert/history flow shared with {@link HardRuleService}. Plain Mockito, no Spring.
 */
class InsurerHardRuleServiceTest {

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository = mock(InsurerRuleHistoryRepository.class);

    private final InsurerHardRuleService service =
            new InsurerHardRuleService(ruleRepository, historyRepository);

    /**
     * The panel always shows both rules: the one the insurer never configured comes back
     * disabled, and arrears defaults to STANDBY (the behavior before this rule existed) rather
     * than leaving onArrears null.
     */
    @Test
    void getReturnsBothRulesEvenWithNothingConfigured() {
        when(ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(any()))
                .thenReturn(List.of());

        List<InsurerHardRuleDto> rules = service.get();

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(InsurerHardRuleDto::ruleType)
                .containsExactlyElementsOf(RuleType.insurerScoped());
        assertThat(rules).allMatch(r -> !r.enabled());
        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.POLICY_STANDING)
                .singleElement().matches(r -> "STANDBY".equals(r.onArrears()));
        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.POLICY_IN_FORCE)
                .singleElement().matches(r -> r.onArrears() == null);
    }

    @Test
    void getReturnsTheConfiguredOnArrearsChoice() {
        when(ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(any()))
                .thenReturn(List.of(rule(9L, RuleType.POLICY_STANDING, true, "{\"onArrears\":\"REJECT\"}")));

        List<InsurerHardRuleDto> rules = service.get();

        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.POLICY_STANDING)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.enabled()).isTrue();
                    assertThat(r.onArrears()).isEqualTo("REJECT");
                });
    }

    @Test
    void getPolicyStandingReturnsDisabledWhenNeverConfigured() {
        when(ruleRepository.findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType("POLICY_STANDING"))
                .thenReturn(Optional.empty());

        InsurerHardRuleDto rule = service.getPolicyStanding();

        assertThat(rule.enabled()).isFalse();
        assertThat(rule.onArrears()).isEqualTo("STANDBY");
    }

    @Test
    void getPolicyStandingReturnsTheConfiguredRule() {
        when(ruleRepository.findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType("POLICY_STANDING"))
                .thenReturn(Optional.of(rule(9L, RuleType.POLICY_STANDING, true, "{\"onArrears\":\"REJECT\"}")));

        InsurerHardRuleDto rule = service.getPolicyStanding();

        assertThat(rule.enabled()).isTrue();
        assertThat(rule.onArrears()).isEqualTo("REJECT");
    }

    @Test
    void upsertCreatesTheRuleWithNoBranchOrCoverage() {
        when(ruleRepository.findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(anyString()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(any()))
                .thenReturn(List.of());

        service.upsert(List.of(new InsurerHardRuleDto(RuleType.POLICY_STANDING, true, "REJECT")), "referente@bbva.com");

        verify(historyRepository, never()).save(any());
        ArgumentCaptor<InsurerRule> captor = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(captor.capture());
        InsurerRule saved = captor.getValue();
        assertThat(saved.getRuleType()).isEqualTo("POLICY_STANDING");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getBranch()).isNull();
        assertThat(saved.getCoverageId()).isNull();
        assertThat(saved.getConfiguration()).contains("\"onArrears\":\"REJECT\"");
    }

    @Test
    void upsertSnapshotsHistoryAndUpdatesWhenRuleExists() {
        InsurerRule existing = rule(9L, RuleType.POLICY_STANDING, true, "{\"onArrears\":\"STANDBY\"}");
        when(ruleRepository.findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType("POLICY_STANDING"))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(any()))
                .thenReturn(List.of(existing));

        service.upsert(List.of(new InsurerHardRuleDto(RuleType.POLICY_STANDING, true, "REJECT")), "referente@bbva.com");

        ArgumentCaptor<InsurerRuleHistory> history = ArgumentCaptor.forClass(InsurerRuleHistory.class);
        verify(historyRepository).save(history.capture());
        // The snapshot keeps the version that got overwritten, not the new one.
        assertThat(history.getValue().getConfigVersion()).contains("STANDBY");
        assertThat(existing.getConfiguration()).contains("REJECT");
    }

    /** POLICY_IN_FORCE has no onArrears choice: a request that sends one is ignored, not rejected. */
    @Test
    void onArrearsOnPolicyInForce_isIgnored() {
        when(ruleRepository.findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(anyString()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(any()))
                .thenReturn(List.of());

        service.upsert(List.of(new InsurerHardRuleDto(RuleType.POLICY_IN_FORCE, true, "REJECT")), "referente@bbva.com");

        ArgumentCaptor<InsurerRule> captor = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().getConfiguration()).contains("\"onArrears\":null");
    }

    /** This endpoint is only for the insurer-scoped rules; the coverage-scoped ones have their own. */
    @Test
    void aCoverageScopedRuleType_isRejected() {
        assertThatThrownBy(() -> service.upsert(
                List.of(new InsurerHardRuleDto(RuleType.WAITING_PERIOD, true, null)), "referente@bbva.com"))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("not an insurer-scoped hard rule");
    }

    private InsurerRule rule(Long id, RuleType type, boolean active, String configuration) {
        return InsurerRule.builder()
                .id(id)
                .ruleType(type.name())
                .active(active)
                .configuration(configuration)
                .build();
    }
}
