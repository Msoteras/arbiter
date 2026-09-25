package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeEntry;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeKind;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeSource;
import ar.edu.utn.frba.arbiter.rules.dto.RuleFieldChange;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository ruleHistoryRepository = mock(InsurerRuleHistoryRepository.class);
    private final ScoringConfigurationRepository scoringConfigurationRepository =
            mock(ScoringConfigurationRepository.class);
    private final ScoringConfigurationHistoryRepository scoringHistoryRepository =
            mock(ScoringConfigurationHistoryRepository.class);
    private final ScoringConfigurationService scoringConfigurationService = mock(ScoringConfigurationService.class);
    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final InsurerReferentRepository insurerReferentRepository = mock(InsurerReferentRepository.class);

    private final RuleChangeHistoryService service = new RuleChangeHistoryService(
            ruleRepository, ruleHistoryRepository, scoringConfigurationRepository, scoringHistoryRepository,
            scoringConfigurationService,
            coverageRepository, claimCauseRepository, userRepository, insurerReferentRepository);

    @Test
    void actorIsTheTextAfterTheLastSeparator() {
        assertThat(RuleChangeHistoryService.actorOf("Fast Track actualizado por ana@bbva.com"))
                .isEqualTo("ana@bbva.com");
        assertThat(RuleChangeHistoryService.actorOf("Hard rule X updated by ana@bbva.com"))
                .isEqualTo("ana@bbva.com");
        assertThat(RuleChangeHistoryService.actorOf("Cambiada por pedido del área por ana@bbva.com"))
                .isEqualTo("ana@bbva.com");
        assertThat(RuleChangeHistoryService.actorOf("Actualización automática")).isNull();
        assertThat(RuleChangeHistoryService.actorOf("Fast Track actualizado por ")).isNull();
        assertThat(RuleChangeHistoryService.actorOf(null)).isNull();
    }

    /**
     * Two snapshots and a live rule are three versions, so two changes: the oldest snapshot pairs
     * with the middle one, not with the live rule, or the intermediate edit disappears.
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

        List<RuleChangeEntry> entries = updates();

        assertThat(entries).hasSize(2);
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

        // Newest first: both changes, then the creation.
        assertThat(page().getContent()).extracting(RuleChangeEntry::current).containsExactly(true, false, false);
    }

    /** Turning a rule off changes {@code active} and nothing in the configuration; it still has to show. */
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

    /** The free-text rules store a bare JSON array, which has no key, so it needs a named field. */
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
     * Legacy rows hold the bare configuration without {@code active}; read naively, that would show
     * a state change nobody made. Only the real difference may show.
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

    /** A stored row for a save that left the rule as it was is not a change and is hidden. */
    @Test
    void leavesOutASaveThatChangedNothing() {
        InsurerRule untouched = policeDeadlineRule("{\"deadlineHours\":72}", true);
        InsurerRule edited = InsurerRule.builder()
                .id(8L).name("Tope de eventos por año").ruleType(RuleType.MAX_EVENTS_YEAR.name())
                .active(true).blocksFastTrack(true).coverageId(9L)
                .configuration("{\"deadlineHours\":96}").validFrom(T1).build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                // Identical before and after: the referente saved without touching this one.
                history(1L, untouched, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}"),
                history(2L, edited, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        List<RuleChangeEntry> entries = updates();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).changes())
                .containsExactly(new RuleFieldChange("deadlineHours", "72", "96"));
    }

    /**
     * A legacy row whose parameters held can't say what changed (the on/off switch wasn't stored
     * then), so it's left out; the rule's creation carries the version in force instead.
     */
    @Test
    void dropsALegacySaveThatCannotSayWhatChanged() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":72}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"deadlineHours\":72}")));
        noScoringHistory();

        assertThat(page().getContent()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo(RuleChangeKind.CREATED);
                    assertThat(entry.current()).isTrue();
                });
    }

    /** A rule never edited still shows up: its creation, dated when it took effect. */
    @Test
    void showsTheCreationOfARuleWithNoChanges() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":72}", true);
        when(ruleRepository.findAllForHistory()).thenReturn(List.of(rule));
        noScoringHistory();

        assertThat(page().getContent()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo(RuleChangeKind.CREATED);
                    assertThat(entry.changedAt()).isEqualTo(T1);
                    assertThat(entry.changes()).isEmpty();
                    assertThat(entry.current()).isTrue();
                });
    }

    /** The live rule's validFrom moves with each edit; the creation is when the first version started. */
    @Test
    void datesTheCreationByTheFirstStoredVersion() {
        Instant created = Instant.parse("2026-01-15T10:00:00Z");
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":96}", true);
        when(ruleRepository.findAllForHistory()).thenReturn(List.of(rule));
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, created, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        List<RuleChangeEntry> entries = page().getContent();

        assertThat(entries).extracting(RuleChangeEntry::kind)
                .containsExactly(RuleChangeKind.UPDATED, RuleChangeKind.CREATED);
        assertThat(entries.get(1).changedAt()).isEqualTo(created);
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
        // Both sides are read back from JSON text, so weights compare by value, not trailing zeros.
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

        assertThat(updates()).extracting(RuleChangeEntry::source)
                .containsExactly(RuleChangeSource.INSURER_RULE, RuleChangeSource.SCORING);
    }

    /** The filter offers the types of the existing rules, via its own query instead of loading the whole history. */
    @Test
    void listsRuleTypesWithoutRereadingTheWholeTrail() {
        when(ruleRepository.findDistinctRuleTypes())
                .thenReturn(List.of(RuleType.POLICE_DEADLINE.name()));
        when(scoringConfigurationRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThat(service.ruleTypes()).containsExactly(RuleType.POLICE_DEADLINE.name());
        verify(ruleHistoryRepository, never()).findAllForHistory();
    }

    /** Excluded claim causes are stored as ids but shown by name, which is what the referente picked. */
    @Test
    void resolvesClaimCauseIdsToTheirNames() {
        InsurerRule rule = InsurerRule.builder()
                .id(7L).name("Exclusiones de la cobertura").ruleType(RuleType.COVERAGE_EXCLUSION.name())
                .active(true).blocksFastTrack(true)
                .branch(Branch.builder().id(2L).name("Celulares").build())
                .coverageId(9L).configuration("{\"excludedClaimCauseIds\":[4,1]}").validFrom(T1).build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"excludedClaimCauseIds\":[3]}}")));
        when(claimCauseRepository.findAll()).thenReturn(List.of(
                claimCause(1L, "Hurto"), claimCause(3L, "Robo en vía pública"), claimCause(4L, "Caída")));
        noScoringHistory();

        assertThat(page().getContent().get(0).changes()).containsExactly(new RuleFieldChange(
                "excludedClaimCauseIds", "Robo en vía pública", "Caída · Hurto"));
    }

    /** Repair derivation stores its claim causes under a different key, but they are ids all the same. */
    @Test
    void resolvesTheRepairDerivationClaimCausesToo() {
        InsurerRule rule = InsurerRule.builder()
                .id(8L).name("Derivación a reparación").ruleType("REPAIR_DERIVATION")
                .active(true).blocksFastTrack(false)
                .branch(Branch.builder().id(2L).name("Celulares").build())
                .configuration("{\"claimCauseIds\":[4]}").validFrom(T1).build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":false,"
                        + "\"configuration\":{\"claimCauseIds\":[1]}}")));
        when(claimCauseRepository.findAll()).thenReturn(List.of(
                claimCause(1L, "Hurto"), claimCause(4L, "Caída")));
        noScoringHistory();

        assertThat(page().getContent().get(0).changes()).containsExactly(new RuleFieldChange(
                "claimCauseIds", "Hurto", "Caída"));
    }

    /** An id with no catalog entry stays as it was: the change happened over it either way. */
    @Test
    void leavesAClaimCauseIdThatNoLongerResolves() {
        InsurerRule rule = InsurerRule.builder()
                .id(7L).name("Exclusiones de la cobertura").ruleType(RuleType.COVERAGE_EXCLUSION.name())
                .active(true).blocksFastTrack(true).coverageId(9L)
                .configuration("{\"excludedClaimCauseIds\":[99]}").validFrom(T1).build();
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"excludedClaimCauseIds\":[]}}")));
        when(claimCauseRepository.findAll()).thenReturn(List.of());
        noScoringHistory();

        assertThat(page().getContent().get(0).changes().get(0).newValue()).isEqualTo("99");
    }

    /** The scoring row's own id is internal and must not show up as a change. */
    @Test
    void keepsTheScoringRowIdOutOfTheDiff() {
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of());
        ScoringConfiguration config = ScoringConfiguration.builder()
                .id(1L).name("Scoring de la aseguradora").build();
        when(scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc()).thenReturn(List.of(
                scoringHistory(config, T1, T2,
                        "{\"enabled\":true,\"fullAnalysisOnFastTrack\":false,\"factors\":[],\"bands\":[]}")));
        when(scoringConfigurationService.get())
                .thenReturn(new ScoringConfigDto(1L, false, false, List.of(), List.of()));

        assertThat(page().getContent().get(0).changes())
                .extracting(RuleFieldChange::field)
                .containsExactly("enabled");
    }

    /**
     * A factor is keyed by its code whatever property the stored JSON puts first; otherwise every
     * factor would read as removed and re-added.
     */
    @Test
    void keysFactorsByCodeEvenWhenTheStoredJsonOrdersPropertiesDifferently() {
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of());
        ScoringConfiguration config = ScoringConfiguration.builder()
                .id(1L).name("Scoring de la aseguradora").build();
        when(scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc()).thenReturn(List.of(
                scoringHistory(config, T1, T2,
                        "{\"enabled\":true,\"fullAnalysisOnFastTrack\":false,"
                                + "\"factors\":[{\"weight\":0.20,\"factorId\":\"IMAGE_REUSED\"}],\"bands\":[]}")));
        when(scoringConfigurationService.get()).thenReturn(new ScoringConfigDto(
                1L, true, false,
                List.of(new ar.edu.utn.frba.arbiter.rules.dto.FactorWeightDto(
                        "IMAGE_REUSED", new java.math.BigDecimal("0.40"))),
                List.of()));

        assertThat(page().getContent().get(0).changes())
                .containsExactly(new RuleFieldChange("factors[IMAGE_REUSED].weight", "0.2", "0.4"));
    }

    /** The author is the referente recorded in changed_by, not whoever the reason happens to name. */
    @Test
    void namesTheAuthorFromChangedBy() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        InsurerRuleHistory row = history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                + "\"configuration\":{\"deadlineHours\":72}}");
        row.setChangedBy(11L);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(row));
        when(insurerReferentRepository.findAllById(any()))
                .thenReturn(List.of(referent(11L, "Ana", "Pérez", 3L)));
        noScoringHistory();

        assertThat(page().getContent().get(0).author()).isEqualTo("Ana Pérez");
        verify(userRepository, never()).findByEmailIn(any());
    }

    /** Rows saved without changed_by still get an author, from the email the reason ends with. */
    @Test
    void fallsBackToTheReasonWhenChangedByIsNull() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        when(userRepository.findByEmailIn(any()))
                .thenReturn(List.of(User.builder().id(3L).email("referente@bbva.com").build()));
        when(insurerReferentRepository.findByUser_IdIn(any()))
                .thenReturn(List.of(referent(11L, "Luis", "Gómez", 3L)));
        noScoringHistory();

        assertThat(page().getContent().get(0).author()).isEqualTo("Luis Gómez");
        verify(insurerReferentRepository, never()).findAllById(any());
    }

    /** Without a referente profile behind the email, the email itself is the author. */
    @Test
    void showsTheEmailWhenTheReasonNamesNoReferente() {
        InsurerRule rule = policeDeadlineRule("{\"deadlineHours\":120}", true);
        when(ruleHistoryRepository.findAllForHistory()).thenReturn(List.of(
                history(1L, rule, T1, T2, "{\"active\":true,\"blocksFastTrack\":true,"
                        + "\"configuration\":{\"deadlineHours\":72}}")));
        noScoringHistory();

        assertThat(page().getContent().get(0).author()).isEqualTo("referente@bbva.com");
    }

    private static InsurerReferent referent(Long id, String name, String surname, Long userId) {
        return InsurerReferent.builder()
                .id(id)
                .name(name)
                .surname(surname)
                .user(User.builder().id(userId).build())
                .build();
    }

    private static ClaimCause claimCause(Long id, String name) {
        ClaimCause cause = new ClaimCause();
        cause.setId(id);
        cause.setName(name);
        return cause;
    }

    private Page<RuleChangeEntry> page() {
        return service.find(null, null, null, null, PageRequest.of(0, 20));
    }

    /** The feed without the creations, for tests about how versions pair into changes. */
    private List<RuleChangeEntry> updates() {
        return page().getContent().stream()
                .filter(entry -> entry.kind() == RuleChangeKind.UPDATED)
                .toList();
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
