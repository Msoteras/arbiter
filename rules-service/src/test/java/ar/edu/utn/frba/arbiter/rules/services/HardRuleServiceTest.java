package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.HardRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The referente's hard temporal rules: the full catalog on get, upsert with a history snapshot,
 * and the threshold validations. Plain Mockito, no Spring.
 */
class HardRuleServiceTest {

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository = mock(InsurerRuleHistoryRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);

    private final HardRuleService service =
            new HardRuleService(ruleRepository, historyRepository, branchRepository);

    /**
     * The panel always shows the four coverage-scoped rules: the one the insurer never configured
     * comes back disabled, exactly how the engine behaves (it doesn't evaluate it). If get
     * returned only the configured ones, the referente would have nowhere to turn on the missing
     * ones. Coverage window and arrears aren't here — they're insurer-scoped, see
     * {@link InsurerHardRuleServiceTest}.
     */
    @Test
    void getReturnsTheWholeCatalogEvenWithNothingConfigured() {
        when(ruleRepository.findByBranch_IdAndCoverageIdAndRuleTypeIn(eq(1L), eq(1L), any()))
                .thenReturn(List.of());

        List<HardRuleDto> rules = service.get(1L, 1L);

        assertThat(rules).hasSize(4);
        assertThat(rules).extracting(HardRuleDto::ruleType)
                .containsExactlyElementsOf(RuleType.coverageScoped());
        assertThat(rules).allMatch(r -> !r.enabled());
        assertThat(rules).allMatch(r -> r.deadlineHours() == null);
    }

    /**
     * The panel sends all four rules on every save, so most of what arrives is untouched. Writing
     * an audit row for each of those turned one real edit into six entries in the referente's
     * history, five of them saying nothing happened.
     */
    @Test
    void doesNotAuditASaveThatLeavesTheRuleAsItWas() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(1L, 1L, "POLICE_DEADLINE"))
                .thenReturn(Optional.of(rule(7L, RuleType.POLICE_DEADLINE, true, "{\"deadlineHours\":72}")));

        service.upsert(1L, 1L, List.of(
                new HardRuleDto(RuleType.POLICE_DEADLINE, true, 72L)), "referente@bbva.com");

        verify(historyRepository, never()).save(any(InsurerRuleHistory.class));
        verify(ruleRepository, never()).save(any(InsurerRule.class));
    }

    @Test
    void getReturnsTheConfiguredStateAndThreshold() {
        when(ruleRepository.findByBranch_IdAndCoverageIdAndRuleTypeIn(eq(1L), eq(1L), any()))
                .thenReturn(List.of(
                        rule(4L, RuleType.REPORT_DEADLINE, true, "{}"),
                        rule(7L, RuleType.POLICE_DEADLINE, true, "{\"deadlineHours\":120}"),
                        rule(8L, RuleType.MAX_EVENTS_YEAR, false, "{}")));

        List<HardRuleDto> rules = service.get(1L, 1L);

        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.POLICE_DEADLINE)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.enabled()).isTrue();
                    assertThat(r.deadlineHours()).isEqualTo(120L);
                });
        // An inactive row and a missing row mean the same thing to the engine.
        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.MAX_EVENTS_YEAR)
                .singleElement().matches(r -> !r.enabled());
        assertThat(rules).filteredOn(r -> r.ruleType() == RuleType.WAITING_PERIOD)
                .singleElement().matches(r -> !r.enabled());
    }

    @Test
    void upsertCreatesTheRuleWhenNoneExists() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mock(Branch.class)));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdAndCoverageIdAndRuleTypeIn(eq(1L), eq(1L), any()))
                .thenReturn(List.of());

        service.upsert(1L, 1L, List.of(new HardRuleDto(RuleType.POLICE_DEADLINE, true, 96L)), "referente@bbva.com");

        verify(historyRepository, never()).save(any());
        ArgumentCaptor<InsurerRule> captor = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(captor.capture());
        InsurerRule saved = captor.getValue();
        assertThat(saved.getRuleType()).isEqualTo("POLICE_DEADLINE");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isBlocksFastTrack()).isTrue();
        // Doesn't reject on its own: a failed hard rule derives to the analyst (human-in-the-loop).
        assertThat(saved.getEffect()).isEqualTo("DERIVAR");
        assertThat(saved.getConfiguration()).contains("\"deadlineHours\":96");
    }

    @Test
    void upsertSnapshotsHistoryAndUpdatesWhenRuleExists() {
        InsurerRule existing = rule(7L, RuleType.POLICE_DEADLINE, true, "{\"deadlineHours\":72}");
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(1L, 1L, "POLICE_DEADLINE"))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdAndCoverageIdAndRuleTypeIn(eq(1L), eq(1L), any()))
                .thenReturn(List.of(existing));

        service.upsert(1L, 1L, List.of(new HardRuleDto(RuleType.POLICE_DEADLINE, true, 120L)), "referente@bbva.com");

        ArgumentCaptor<InsurerRuleHistory> history = ArgumentCaptor.forClass(InsurerRuleHistory.class);
        verify(historyRepository).save(history.capture());
        // The snapshot keeps the version that got overwritten, not the new one.
        assertThat(history.getValue().getConfigVersion()).contains("72");
        assertThat(existing.getConfiguration()).contains("120");
    }

    /** Turning a rule off doesn't delete the row: it stays inactive, and history keeps the change. */
    @Test
    void disablingARuleKeepsTheRowInactive() {
        InsurerRule existing = rule(4L, RuleType.WAITING_PERIOD, true, "{}");
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(1L, 1L, "WAITING_PERIOD"))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByBranch_IdAndCoverageIdAndRuleTypeIn(eq(1L), eq(1L), any()))
                .thenReturn(List.of(existing));

        service.upsert(1L, 1L, List.of(new HardRuleDto(RuleType.WAITING_PERIOD, false, null)), "referente@bbva.com");

        assertThat(existing.isActive()).isFalse();
        verify(historyRepository).save(any(InsurerRuleHistory.class));
    }

    /**
     * Turning on the police deadline without loading the number would leave a rule that claims to
     * be active but that the engine still never evaluates: rejected on save instead of failing
     * silently.
     */
    @Test
    void enablingThePoliceDeadlineWithoutAThreshold_isRejected() {
        assertThatThrownBy(() -> service.upsert(
                1L, 1L, List.of(new HardRuleDto(RuleType.POLICE_DEADLINE, true, null)), "referente@bbva.com"))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("hour threshold");
    }

    @Test
    void aNegativeThreshold_isRejected() {
        assertThatThrownBy(() -> service.upsert(
                1L, 1L, List.of(new HardRuleDto(RuleType.POLICE_DEADLINE, true, -1L)), "referente@bbva.com"))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("can't be negative");
    }

    /** This endpoint is only for the coverage-scoped hard rules; Fast Track has its own. */
    @Test
    void aRuleTypeThatIsNotATemporalHardRule_isRejected() {
        assertThatThrownBy(() -> service.upsert(
                1L, 1L, List.of(new HardRuleDto(RuleType.FAST_TRACK, true, null)), "referente@bbva.com"))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("not a coverage-scoped hard rule");
    }

    /**
     * POLICY_STANDING (arrears) is insurer-scoped now, not coverage-scoped: this endpoint rejects
     * it the same way it rejects FAST_TRACK. See {@link InsurerHardRuleServiceTest}.
     */
    @Test
    void anInsurerScopedRuleType_isRejectedHere() {
        assertThatThrownBy(() -> service.upsert(
                1L, 1L, List.of(new HardRuleDto(RuleType.POLICY_STANDING, true, null)), "referente@bbva.com"))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("not a coverage-scoped hard rule");
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
