package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionResponse;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hard coverage exclusions: get/upsert per (branch, coverage) with a history snapshot, and the
 * claim cause catalog. Plain Mockito, no Spring.
 */
class CoverageExclusionRuleServiceTest {

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository = mock(InsurerRuleHistoryRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);

    private final CoverageExclusionRuleService service = new CoverageExclusionRuleService(
            ruleRepository, historyRepository, branchRepository, claimCauseRepository);

    @Test
    void getReturnsConfiguredExclusions() {
        InsurerRule rule = InsurerRule.builder()
                .id(3L)
                .ruleType("COVERAGE_EXCLUSION")
                .configuration("{\"excludedClaimCauseIds\":[3,8]}")
                .build();
        when(ruleRepository.findFirstByCoverageIdAndRuleType(1L, "COVERAGE_EXCLUSION"))
                .thenReturn(Optional.of(rule));

        assertThat(service.get(1L).excludedClaimCauseIds()).containsExactly(3L, 8L);
    }

    @Test
    void getReturnsEmptyWhenNoRule() {
        when(ruleRepository.findFirstByCoverageIdAndRuleType(1L, "COVERAGE_EXCLUSION"))
                .thenReturn(Optional.empty());

        assertThat(service.get(1L).excludedClaimCauseIds()).isEmpty();
    }

    @Test
    void upsertCreatesRuleWhenNoneExists() {
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(1L, 1L, "COVERAGE_EXCLUSION"))
                .thenReturn(Optional.empty());
        when(branchRepository.findById(1L)).thenReturn(Optional.of(mock(Branch.class)));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> {
            InsurerRule r = inv.getArgument(0);
            r.setId(3L);
            return r;
        });

        CoverageExclusionResponse response =
                service.upsert(1L, 1L, new CoverageExclusionConfig(List.of(3L)), "referente@bbva.com");

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.excludedClaimCauseIds()).containsExactly(3L);
        verify(historyRepository, never()).save(any());

        ArgumentCaptor<InsurerRule> captor = ArgumentCaptor.forClass(InsurerRule.class);
        verify(ruleRepository).save(captor.capture());
        InsurerRule saved = captor.getValue();
        assertThat(saved.getRuleType()).isEqualTo("COVERAGE_EXCLUSION");
        assertThat(saved.isBlocksFastTrack()).isTrue();
        assertThat(saved.getConfiguration()).contains("\"excludedClaimCauseIds\"").contains("3");
    }

    @Test
    void upsertSnapshotsHistoryAndUpdatesWhenRuleExists() {
        InsurerRule existing = InsurerRule.builder()
                .id(3L)
                .ruleType("COVERAGE_EXCLUSION")
                .configuration("{\"excludedClaimCauseIds\":[3]}")
                .build();
        when(ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(1L, 1L, "COVERAGE_EXCLUSION"))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(InsurerRule.class))).thenAnswer(inv -> inv.getArgument(0));

        CoverageExclusionResponse response =
                service.upsert(1L, 1L, new CoverageExclusionConfig(List.of()), "referente@bbva.com");

        verify(historyRepository).save(any(InsurerRuleHistory.class));
        assertThat(existing.getConfiguration()).contains("[]");
        assertThat(response.excludedClaimCauseIds()).isEmpty();
    }

    @Test
    void listClaimCausesMapsIdAndName() {
        ClaimCause hurto = mock(ClaimCause.class);
        when(hurto.getId()).thenReturn(3L);
        when(hurto.getName()).thenReturn("Hurto");
        when(claimCauseRepository.findByBranch_IdOrderByNameAsc(1L)).thenReturn(List.of(hurto));

        assertThat(service.listClaimCauses(1L)).containsExactly(new CatalogOption(3L, "Hurto"));
    }
}
