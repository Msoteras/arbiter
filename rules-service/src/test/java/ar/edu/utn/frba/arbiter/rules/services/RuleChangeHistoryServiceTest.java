package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeEntry;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeSource;
import ar.edu.utn.frba.arbiter.rules.dto.RuleFieldChange;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The referente's rule change history. What's under test is the pairing: a stored snapshot is the
 * version that <i>ended</i>, so a change only exists once it's read together with whatever replaced
 * it. Plain Mockito, no Spring.
 */
class RuleChangeHistoryServiceTest {

    private static final Instant T1 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-04-01T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-01T10:00:00Z");

    private final InsurerRuleHistoryRepository ruleHistoryRepository = mock(InsurerRuleHistoryRepository.class);
    private final ScoringConfigurationHistoryRepository scoringHistoryRepository =
            mock(ScoringConfigurationHistoryRepository.class);
    private final ScoringConfigurationService scoringConfigurationService = mock(ScoringConfigurationService.class);
    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);

    private final RuleChangeHistoryService service = new RuleChangeHistoryService(
            ruleHistoryRepository, scoringHistoryRepository, scoringConfigurationService, coverageRepository);

    /**
     * Two snapshots and a live rule are three versions and therefore two changes. The oldest
     * snapshot has to pair with the middle one — not with the live rule — or the referente reads
     * that the deadline went from 48h straight to 120h and the intermediate edit disappears.
     */
    @Test
    void pairsEachSnapshotWithTheVersionThatReplacedIt() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":48}}"),
                history(2L, rule, T2, T3, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        List<RuleChangeEntry> entries = page().getContent();

        assertThat(entries).hasSize(2);
        // Newest first: the trail is only ever read from the last thing that happened.
        assertThat(entries).extracting(RuleChangeEntry::changedAt).containsExactly(T3, T2);
        assertThat(entries.get(0).changes())
                .containsExactly(new RuleFieldChange("deadlineHours", "72", "120"));
        assertThat(entries.get(1).changes())
                .containsExactly(new RuleFieldChange("deadlineHours", "48", "72"));
    }

    /** Only the change that produced the version in force today is the current one. */
    @Test
    void marksOnlyTheLastChangeOfEachRuleAsCurrent() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":48}}"),
                history(2L, rule, T2, T3, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        assertThat(page().getContent()).extracting(RuleChangeEntry::current).containsExactly(true, false);
    }

    /**
     * Turning a rule off changes {@code active} and nothing else. This is the most common edit the
     * referente makes, and it's exactly the one the trail used to lose: while only the
     * {@code configuration} was snapshotted, before and after came out byte-identical and the
     * change rendered as empty.
     */
    @Test
    void reportsTheOnOffToggleAsAChange() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":72}", false);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        assertThat(page().getContent().get(0).changes())
                .containsExactly(new RuleFieldChange("active", "true", "false"));
    }

    /**
     * The free-text rules store a bare JSON array, not an object, so their configuration has no key
     * of its own to be named after. It has to land under a field the referente can read — an empty
     * label in the diff would leave the change with nowhere to hang.
     */
    @Test
    void namesTheConfigurationOfARuleThatStoresABareList() {
        InsurerRule rule = InsurerRule.builder()
                .id(6L)
                .name("Exclusiones del ramo")
                .ruleType(RuleType.EXCLUSIONS.name())
                .active(true)
                .branch(Branch.builder().id(2L).name("Celulares").build())
                .configuration("[\"Daño estético\",\"Uso comercial\"]")
                .validFrom(T1)
                .build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2,
                        "{\"active\":true,\"blocksFastTrack\":false,\"configuration\":[\"Daño estético\"]}")));
        noScoringHistory();

        List<RuleFieldChange> changes = page().getContent().get(0).changes();

        assertThat(changes).containsExactly(new RuleFieldChange(
                "configuration", "Daño estético", "Daño estético · Uso comercial"));
    }

    /**
     * Rows written before the snapshot carried {@code active} hold the bare configuration. They
     * exist in the shared database, they can't be rewritten (append-only), and read naively their
     * missing {@code active} would surface as "la regla pasó de apagada a encendida" — a state
     * change nobody made, sitting in an audit trail. Only the real difference may show.
     */
    @Test
    void doesNotInventAStateChangeWhenTheStoredRowPredatesTheFlags() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                // Legacy shape: the configuration stored bare, with no active/blocksFastTrack.
                history(1L, rule, T1, T2, "{\"deadlineHours\":72}")));
        noScoringHistory();

        assertThat(page().getContent().get(0).changes())
                .containsExactly(new RuleFieldChange("deadlineHours", "72", "120"));
    }

    /** A legacy row whose configuration was a bare array — how the free-text rules stored it. */
    @Test
    void readsALegacyRowThatStoredABareList() {
        InsurerRule rule = InsurerRule.builder()
                .id(6L).name("Exclusiones del ramo").ruleType(RuleType.EXCLUSIONS.name())
                .active(true).branch(Branch.builder().id(2L).name("Celulares").build())
                .configuration("[\"Daño estético\",\"Uso comercial\"]").validFrom(T1).build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "[\"Daño estético\"]")));
        noScoringHistory();

        assertThat(page().getContent().get(0).changes()).containsExactly(new RuleFieldChange(
                "configuration", "Daño estético", "Daño estético · Uso comercial"));
    }

    /** The scope the referente needs to tell two rules of the same type apart. */
    @Test
    void resolvesBranchAndCoverageNames() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":72}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":48}}")));
        when(coverageRepository.findAll()).thenReturn(List.of(
                Coverage.builder().id(9L).name("Robo total").build()));
        noScoringHistory();

        RuleChangeEntry entry = page().getContent().get(0);

        assertThat(entry.source()).isEqualTo(RuleChangeSource.INSURER_RULE);
        assertThat(entry.ruleType()).isEqualTo(RuleType.POLICE_DEADLINE.name());
        assertThat(entry.branchName()).isEqualTo("Celulares");
        assertThat(entry.coverageName()).isEqualTo("Robo total");
        assertThat(entry.previousValidFrom()).isEqualTo(T1);
    }

    /**
     * A factor weight is identified by its code, never by its position in the array: inserting a
     * factor would otherwise shift every index and report the whole list as changed.
     */
    @Test
    void keysScoringFactorsByCodeAndNotByPosition() {
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of());
        ScoringConfiguration config = ScoringConfiguration.builder()
                .id(1L).name("Scoring de la aseguradora").build();
        when(scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc()).thenReturn(List.of(
                scoringHistory(config, T1, T2,
                        "{\"id\":1,\"enabled\":true,\"fullAnalysisOnFastTrack\":false,"
                                + "\"factors\":[{\"factorId\":\"IMAGE_REUSED\",\"weight\":0.20}],\"bands\":[]}")));
        when(scoringConfigurationService.get()).thenReturn(new ScoringConfigDto(
                1L, true, false,
                List.of(new ar.edu.utn.frba.arbiter.rules.dto.FactorWeightDto(
                        "IMAGE_REUSED", new java.math.BigDecimal("0.40"))),
                List.of()));

        List<RuleFieldChange> changes = page().getContent().get(0).changes();

        assertThat(changes).extracting(RuleFieldChange::field)
                .containsExactly("factors[IMAGE_REUSED].weight");
        // "0.4" and not "0.40": both sides are read back from JSON text, so a weight is compared
        // by its value and not by how many trailing zeros whoever saved it happened to type.
        assertThat(changes.get(0).previousValue()).isEqualTo("0.2");
        assertThat(changes.get(0).newValue()).isEqualTo("0.4");
    }

    /** Both trails are one feed for the referente, in real time order and not one table after the other. */
    @Test
    void mergesBothTrailsChronologically() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":72}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T3, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":48}}")));
        ScoringConfiguration config = ScoringConfiguration.builder()
                .id(1L).name("Scoring de la aseguradora").build();
        when(scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc()).thenReturn(List.of(
                scoringHistory(config, T1, T2, "{\"id\":1,\"enabled\":false,\"fullAnalysisOnFastTrack\":false,"
                        + "\"factors\":[],\"bands\":[]}")));
        when(scoringConfigurationService.get())
                .thenReturn(new ScoringConfigDto(1L, true, false, List.of(), List.of()));

        assertThat(page().getContent()).extracting(RuleChangeEntry::source)
                .containsExactly(RuleChangeSource.INSURER_RULE, RuleChangeSource.SCORING);
    }

    /** The filter offers only what the trail holds — anything else would return an empty page. */
    @Test
    void listsOnlyTheRuleTypesPresentInTheTrail() {
        InsurerRule rule = policeDeadlineRule("{}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{}")));
        noScoringHistory();

        assertThat(service.ruleTypes()).containsExactly(RuleType.POLICE_DEADLINE.name());
    }

    private Page<RuleChangeEntry> page() {
        return service.find(null, null, null, null, PageRequest.of(0, 20));
    }

    private void noScoringHistory() {
        when(scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc()).thenReturn(List.of());
    }

    private static InsurerRule policeDeadlineRule(String configuration, boolean active) {
        return InsurerRule.builder()
                .id(5L)
                .name("Plazo de la denuncia policial (cobertura 9)")
                .ruleType(RuleType.POLICE_DEADLINE.name())
                .active(active)
                .blocksFastTrack(true)
                .branch(Branch.builder().id(2L).name("Celulares").build())
                .coverageId(9L)
                .configuration(configuration)
                .validFrom(T1)
                .build();
    }

    private static InsurerRuleHistory history(
            Long id, InsurerRule rule, Instant validFrom, Instant changedAt, String snapshot) {
        return InsurerRuleHistory.builder()
                .id(id)
                .insurerRule(rule)
                .configVersion(snapshot)
                .validFrom(validFrom)
                .validTo(changedAt)
                .changedAt(changedAt)
                .reason("Regla dura actualizada por referente@bbva.com")
                .build();
    }

    private static ScoringConfigurationHistory scoringHistory(
            ScoringConfiguration config, Instant validFrom, Instant changedAt, String snapshot) {
        return ScoringConfigurationHistory.builder()
                .id(1L)
                .scoringConfiguration(config)
                .snapshotConfig(snapshot)
                .validFrom(validFrom)
                .validTo(changedAt)
                .changedAt(changedAt)
                .reason("Scoring actualizado por referente@bbva.com")
                .build();
    }
}
