package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.reports.dto.DerivationTurnaround;
import ar.edu.utn.frba.arbiter.reports.dto.FastTrackImpact;
import ar.edu.utn.frba.arbiter.reports.dto.FraudDetection;
import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.LegalDeadline;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ReopeningRate;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.reports.support.CaseTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.APPROVED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_CELULARES;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_TECNOLOGIA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAPSED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.JUAN;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAURA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PENDING_REVIEW;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.REJECTED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.ROBO_CELULARES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against real Postgres: the {@code DISTINCT ON}, the Fast Track precedence and the time-zone day
 * buckets are the feature.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimMetricsRepositoryTests extends AbstractPersistenceIT {

    private static final MetricsFilter NONE = MetricsFilter.NONE;
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** August 2026 in Buenos Aires: from its first midnight to the next month's, exclusive. */
    private static final Instant AUGUST_FROM = Instant.parse("2026-08-01T03:00:00Z");
    private static final Instant AUGUST_TO = Instant.parse("2026-09-01T03:00:00Z");

    @Autowired
    private ClaimMetricsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CaseTables tables;

    @BeforeAll
    void createTables() {
        tables = new CaseTables(jdbcTemplate);
        tables.create();
        // Only the dashboard reads the risk band, so the shared fixture doesn't carry the column.
        jdbcTemplate.execute("ALTER TABLE cases ADD COLUMN IF NOT EXISTS risk_band VARCHAR(20)");
    }

    @BeforeEach
    void resetTables() {
        tables.reset();
    }

    @Test
    void intake_countsWhatCameInAndHowMuchOfItWasFastTrack() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, true, null, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        // July: outside the window, must stay out of every figure.
        tables.insertCase(4, "2026-07-20T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, true, null, null);

        IntakeTotals totals = repository.intakeTotals(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(totals).isEqualTo(new IntakeTotals(3, 1));
    }

    @Test
    void byStatus_readsInLifecycleOrder() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", APPROVED, HURTO_CELULARES, false, null, null);
        tables.insertCase(4, "2026-08-05T10:00:00Z", REJECTED, HURTO_CELULARES, false, null, null);

        assertThat(repository.countByStatus(AUGUST_FROM, AUGUST_TO, NONE)).containsExactly(
                new MetricCount("PENDING_ANALYST_REVIEW", 1),
                new MetricCount("APPROVED", 2),
                new MetricCount("REJECTED", 1));
    }

    @Test
    void byBranch_groupsEveryCauseOfTheSameBranchTogether() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, HURTO_TECNOLOGIA, false, null, null);

        assertThat(repository.countByBranch(AUGUST_FROM, AUGUST_TO, NONE)).containsExactly(
                new MetricCount("Celulares", 2),
                new MetricCount("Tecnología Portátil", 1));
    }

    @Test
    void byClassification_prefersFastTrack_thenTheNewestRun_andKeepsTheUnclassified() {
        // Fast Track: the model never ran, and an older run of an earlier attempt must not win.
        tables.insertCase(1, "2026-08-02T10:00:00Z", APPROVED, ROBO_CELULARES, true, null, null);
        tables.recommendation(1, "LLM_SOLICITA_REVISION_MANUAL");
        // Append-only: the newest run is the classification.
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.recommendation(2, "LLM_NO_RECOMIENDA_APROBAR");
        tables.recommendation(2, "LLM_RECOMIENDA_APROBAR");
        // Still being classified.
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);

        assertThat(repository.countByClassification(AUGUST_FROM, AUGUST_TO, NONE)).containsExactlyInAnyOrder(
                new MetricCount("FAST_TRACK", 1),
                new MetricCount("LLM_RECOMIENDA_APROBAR", 1),
                new MetricCount(null, 1));
    }

    @Test
    void byRiskBand_keepsTheClaimsScoringNeverReached() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        riskBand(1, "HIGH");
        riskBand(2, "HIGH");

        assertThat(repository.countByRiskBand(AUGUST_FROM, AUGUST_TO, NONE)).containsExactlyInAnyOrder(
                new MetricCount("HIGH", 2),
                new MetricCount(null, 1));
    }

    @Test
    void resolved_countsPerFinalStatusWithItsOwnAverage() {
        // Filed 01/08 10:00Z, approved 03/08 12:30Z — 2 days 2.5 hours.
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-03T12:30:00Z");
        // Filed 01/08 10:00Z, rejected 02/08 10:00Z — 1 day.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-02T10:00:00Z");
        // Still open: it has no closing transition and must not appear.
        tables.insertCase(3, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.transition(3, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");

        assertThat(repository.resolvedTotals(AUGUST_FROM, AUGUST_TO, NONE)).containsExactlyInAnyOrder(
                new ResolvedTotals("APPROVED", 1, (double) (2 * 86_400 + 9_000)),
                new ResolvedTotals("REJECTED", 1, (double) 86_400));
    }

    @Test
    void aClaimReopenedAndClosedAgain_countsOnceOnTheDayItClosedForGood() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        // Rejected, reopened, rejected again: only the last closing is the resolution.
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-08-02T10:00:00Z");
        tables.transition(1, REJECTED, PENDING_REVIEW, "2026-08-03T10:00:00Z");
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-08-05T10:00:00Z");

        assertThat(repository.resolvedTotals(AUGUST_FROM, AUGUST_TO, NONE)).containsExactly(
                new ResolvedTotals("REJECTED", 1, (double) (4 * 86_400)));
    }

    @Test
    void aLapsedClaim_isResolvedToo_andTheServiceDecidesWhatToDoWithIt() {
        tables.insertCase(1, "2025-02-01T13:00:00Z", LAPSED, HURTO_TECNOLOGIA, false, null, null);
        tables.transition(1, PENDING_REVIEW, LAPSED, "2026-08-02T13:00:00Z");

        assertThat(repository.resolvedTotals(AUGUST_FROM, AUGUST_TO, NONE))
                .singleElement()
                .satisfies(totals -> {
                    assertThat(totals.status()).isEqualTo("LAPSED");
                    assertThat(totals.count()).isEqualTo(1);
                });
    }

    @Test
    void theTimeline_bucketsByTheInsurersCalendarDay_notUTCs() {
        // 05/08 02:00 UTC is still 04/08 23:00 in Buenos Aires: the claim belongs to the 4th.
        tables.insertCase(1, "2026-08-05T02:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-08-05T14:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, APPROVED, "2026-08-10T14:00:00Z");

        List<TimelinePoint> timeline =
                repository.timeline(AUGUST_FROM, AUGUST_TO, TimelineGranularity.DAY, BUENOS_AIRES, NONE);

        assertThat(timeline).containsExactly(
                new TimelinePoint(LocalDate.of(2026, 8, 4), 1, 0),
                new TimelinePoint(LocalDate.of(2026, 8, 5), 1, 0),
                new TimelinePoint(LocalDate.of(2026, 8, 10), 0, 1));
    }

    @Test
    void theFunnel_followsThePeriodsIntakeForward_andNeverCountsAFastTrackAsAnalyzed() {
        // Fast Track: decided, but the model never ran — it must NOT count as analyzed.
        tables.decision(1, "APPROVE", LAURA);
        tables.insertCase(1, "2026-08-02T10:00:00Z", APPROVED, ROBO_CELULARES, true, LAURA, 1L);
        // Analyzed and decided.
        tables.decision(2, "REJECT", LAURA);
        tables.insertCase(2, "2026-08-03T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, 2L);
        tables.recommendation(2, "LLM_NO_RECOMIENDA_APROBAR");
        // Analyzed, still on someone's desk.
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.recommendation(3, "LLM_RECOMIENDA_APROBAR");
        // July: out of the cohort entirely.
        tables.insertCase(4, "2026-07-20T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);

        assertThat(repository.intakeFunnel(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(new IntakeFunnel(3, 2, 2, 1, 1));
    }

    @Test
    void agreement_countsOnlyTheClaimsWhoseRecommendationPointedSomewhere() {
        // Recommended approving, ended approved: agreement.
        resolvedWithRecommendation(1, "LLM_RECOMIENDA_APROBAR", APPROVED);
        // Recommended against, approved anyway: the analyst overrode the model.
        resolvedWithRecommendation(2, "LLM_NO_RECOMIENDA_APROBAR", APPROVED);
        // Manual review points nowhere: nothing to agree or disagree with.
        resolvedWithRecommendation(3, "LLM_SOLICITA_REVISION_MANUAL", APPROVED);
        // Lapsed: nobody decided it, so it is not a disagreement either.
        resolvedWithRecommendation(4, "LLM_RECOMIENDA_APROBAR", LAPSED);
        // Fast Track: the model never saw it.
        tables.insertCase(5, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, true, LAURA, null);
        tables.transition(5, PENDING_REVIEW, APPROVED, "2026-08-05T10:00:00Z");

        assertThat(repository.recommendationAgreement(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(new RecommendationAgreement(2, 1, 0.5));
    }

    @Test
    void agreement_isUnknownRatherThanZero_whenNothingDecidedCarriedARecommendation() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, true, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T10:00:00Z");

        assertThat(repository.recommendationAgreement(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(RecommendationAgreement.EMPTY);
    }

    @Test
    void theBranchFilter_cutsToOneBranchAcrossAllOfItsCauses() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, HURTO_TECNOLOGIA, false, null, null);

        // Branch 1 is Celulares, which owns both "Robo en vía pública" and its own "Hurto".
        assertThat(repository.intakeTotals(AUGUST_FROM, AUGUST_TO, new MetricsFilter(1L, null)))
                .isEqualTo(new IntakeTotals(2, 0));
        assertThat(repository.countByBranch(AUGUST_FROM, AUGUST_TO, new MetricsFilter(2L, null)))
                .containsExactly(new MetricCount("Tecnología Portátil", 1));
    }

    @Test
    void theAnalystFilter_cutsByWhoTheCaseIsAssignedTo() {
        tables.insertCase(1, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.insertCase(2, "2026-08-03T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.insertCase(3, "2026-08-04T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, JUAN, null);
        // Unassigned: belongs to nobody's cut.
        tables.insertCase(4, "2026-08-05T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);

        assertThat(repository.intakeTotals(AUGUST_FROM, AUGUST_TO, new MetricsFilter(null, LAURA)))
                .isEqualTo(new IntakeTotals(2, 0));
        assertThat(repository.countByStatus(AUGUST_FROM, AUGUST_TO, new MetricsFilter(null, JUAN)))
                .containsExactly(new MetricCount("PENDING_ANALYST_REVIEW", 1));
    }

    @Test
    void theFilter_reachesTheTimelineToo() {
        tables.insertCase(1, "2026-08-05T14:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.insertCase(2, "2026-08-05T14:00:00Z", PENDING_REVIEW, HURTO_TECNOLOGIA, false, LAURA, null);

        List<TimelinePoint> timeline = repository.timeline(
                AUGUST_FROM, AUGUST_TO, TimelineGranularity.DAY, BUENOS_AIRES, new MetricsFilter(1L, null));

        assertThat(timeline).containsExactly(new TimelinePoint(LocalDate.of(2026, 8, 5), 1, 0));
    }

    @Test
    void overTarget_countsOnlyTheDecisionsThatRanPastIt_andIsStrictAtTheEdge() {
        // Filed 01/08, approved 05/08: exactly 4 days.
        resolvedWithRecommendation(1, "LLM_RECOMIENDA_APROBAR", APPROVED);
        // Filed 01/08, rejected 20/08: 19 days.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-20T10:00:00Z");
        // Lapsed after 25 days: nobody decided it, so it is not over target.
        tables.insertCase(3, "2026-08-01T10:00:00Z", LAPSED, ROBO_CELULARES, false, null, null);
        tables.transition(3, PENDING_REVIEW, LAPSED, "2026-08-26T10:00:00Z");

        // With a 4-day target only the 19-day one exceeds it; the 4-day one meets it exactly.
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 4, NONE)).isEqualTo(1);
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 3, NONE)).isEqualTo(2);
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 30, NONE)).isZero();
    }

    @Test
    void overTarget_discountsWhatTheCaseSpentWaitingOnSomebodyOutside() {
        jdbcTemplate.update(
                "INSERT INTO case_status (id, name, is_final) VALUES (3, 'AWAITING_DOCUMENTATION', FALSE) "
                        + "ON CONFLICT (id) DO NOTHING");
        long awaitingDocs = 3;

        // 10 wall-clock days, 6 of them awaiting documentation: 4 days of handling.
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T00:00:00Z");
        tables.transition(1, PENDING_REVIEW, awaitingDocs, "2026-08-03T00:00:00Z");
        tables.transition(1, awaitingDocs, PENDING_REVIEW, "2026-08-09T00:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-11T00:00:00Z");

        // Over a 5-day target by wall clock, but not by handling time.
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 5, NONE)).isZero();
        // Below the 4 handling days, it does exceed.
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 3, NONE)).isEqualTo(1);
    }

    /** The shared fixture only seeds four statuses, so AWAITING_DOCUMENTATION is added here. */
    @Test
    void theSplit_discountsTheTimeSpentWaitingOnSomebodyOutside() {
        jdbcTemplate.update(
                "INSERT INTO case_status (id, name, is_final) VALUES (3, 'AWAITING_DOCUMENTATION', FALSE) "
                        + "ON CONFLICT (id) DO NOTHING");
        long awaitingDocs = 3;

        // Filed 01/08, approved 11/08: 10 wall-clock days, 6 of them (03/08 to 09/08) awaiting
        // documentation from the insured.
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T00:00:00Z");
        tables.transition(1, PENDING_REVIEW, awaitingDocs, "2026-08-03T00:00:00Z");
        tables.transition(1, awaitingDocs, PENDING_REVIEW, "2026-08-09T00:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-11T00:00:00Z");

        ResolutionSplit split = repository.resolutionSplit(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(split.totalSeconds()).isEqualTo(10 * 86_400d);
        assertThat(split.waitingSeconds()).isEqualTo(6 * 86_400d);
    }

    @Test
    void theSplit_isEmptyWhenNothingWasDecidedInThePeriod() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);

        assertThat(repository.resolutionSplit(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(ResolutionSplit.NONE);
    }

    @Test
    void theSplit_reportsZeroWaitingWhenNobodyWasEverWaitedOn() {
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T00:00:00Z");

        ResolutionSplit split = repository.resolutionSplit(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(split.totalSeconds()).isEqualTo(4 * 86_400d);
        assertThat(split.waitingSeconds()).isZero();
    }

    @Test
    void theLegalTerm_comparesEachDecisionAgainstTheDeadlineTheCaseCarried() {
        // Decided 05/08, due 10/08: on time.
        resolvedWithRecommendation(1, "LLM_RECOMIENDA_APROBAR", APPROVED);
        tables.deadline(1, "2026-08-10");
        // Decided 20/08, due 15/08: late.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-20T10:00:00Z");
        tables.deadline(2, "2026-08-15");
        // Lapsed: no decision to date, so it is out of the compliance figure.
        tables.insertCase(3, "2026-08-01T10:00:00Z", LAPSED, ROBO_CELULARES, false, null, null);
        tables.transition(3, PENDING_REVIEW, LAPSED, "2026-08-26T10:00:00Z");
        tables.deadline(3, "2026-08-02");

        assertThat(repository.legalDeadlineCompliance(AUGUST_FROM, AUGUST_TO, BUENOS_AIRES, NONE))
                .isEqualTo(LegalDeadline.of(2, 1));
    }

    @Test
    void theLegalTerm_countsTheDayOfTheDeadlineItselfAsInTime() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        // 22:00 in Buenos Aires on the due date, already the next day in UTC.
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-11T01:00:00Z");
        tables.deadline(1, "2026-08-10");

        assertThat(repository.legalDeadlineCompliance(AUGUST_FROM, AUGUST_TO, BUENOS_AIRES, NONE))
                .isEqualTo(LegalDeadline.of(1, 1));
    }

    @Test
    void theLegalTerm_hasNoRateWhenNothingWasDecided() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);

        assertThat(repository.legalDeadlineCompliance(AUGUST_FROM, AUGUST_TO, BUENOS_AIRES, NONE))
                .isEqualTo(new LegalDeadline(0, 0, null));
    }

    @Test
    void reopening_countsCasesThatCameBackFromAFinalStatus_onceEach() {
        // Approved, reopened, approved again: one reopened case.
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T10:00:00Z");
        tables.transition(1, APPROVED, PENDING_REVIEW, "2026-08-06T10:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-10T10:00:00Z");
        // Rejected, reopened twice, rejected: still one reopened case.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-04T10:00:00Z");
        tables.transition(2, REJECTED, PENDING_REVIEW, "2026-08-05T10:00:00Z");
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-06T10:00:00Z");
        tables.transition(2, REJECTED, PENDING_REVIEW, "2026-08-07T10:00:00Z");
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-12T10:00:00Z");
        // Closed once, never reopened.
        tables.insertCase(3, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(3, PENDING_REVIEW, APPROVED, "2026-08-08T10:00:00Z");

        assertThat(repository.reopeningRate(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(ReopeningRate.of(3, 2));
    }

    @Test
    void reopening_readsEveryClosedCase_lapsedOnesIncluded() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", LAPSED, ROBO_CELULARES, false, null, null);
        tables.transition(1, PENDING_REVIEW, LAPSED, "2026-08-20T10:00:00Z");

        assertThat(repository.reopeningRate(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(ReopeningRate.of(1, 0));
    }

    @Test
    void settled_addsUpOnlyTheAuthorisedSettlementsConfirmedInThePeriod() {
        tables.insertCase(1, "2026-07-20T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.claimed(1, "500000.00");
        tables.settlement(1, "420000.00", "AUTHORIZED", "2026-08-05T10:00:00Z",
                "50000.00", "20000.00", "10000.00");
        tables.insertCase(2, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.claimed(2, "300000.00");
        tables.settlement(2, "280000.00", "AUTHORIZED", "2026-08-20T10:00:00Z", "20000.00", "0", "0");
        // Awaiting the referent's signature: not a commitment yet.
        tables.insertCase(3, "2026-08-02T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.claimed(3, "900000.00");
        tables.settlement(3, "850000.00", "PENDING_AUTHORIZATION", "2026-08-21T10:00:00Z", "0", "0", "0");
        // Authorized, but in September: next period.
        tables.insertCase(4, "2026-08-03T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.settlement(4, "100000.00", "AUTHORIZED", "2026-09-02T10:00:00Z", "0", "0", "0");

        SettledAmounts settled = repository.settledAmounts(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(settled.settlements()).isEqualTo(2);
        assertThat(settled.settled()).isEqualByComparingTo("700000.00");
        assertThat(settled.average()).isEqualByComparingTo("350000.00");
        assertThat(settled.claimed()).isEqualByComparingTo("800000.00");
        assertThat(settled.claimedCases()).isEqualTo(2);
        assertThat(settled.deductible()).isEqualByComparingTo("70000.00");
        assertThat(settled.installments()).isEqualByComparingTo("20000.00");
        assertThat(settled.overdue()).isEqualByComparingTo("10000.00");
    }

    @Test
    void settled_saysOnHowManySettlementsTheClaimedAmountCouldBeAddedUp() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.claimed(1, "300000.00");
        tables.settlement(1, "280000.00", "AUTHORIZED", "2026-08-05T10:00:00Z", "0", "0", "0");
        // No claimed amount: counts as a settlement but not toward the claimed total.
        tables.insertCase(2, "2026-08-02T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.settlement(2, "400000.00", "AUTHORIZED", "2026-08-06T10:00:00Z", "0", "0", "0");

        SettledAmounts settled = repository.settledAmounts(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(settled.settlements()).isEqualTo(2);
        assertThat(settled.claimedCases()).isEqualTo(1);
    }

    @Test
    void settled_hasNoAverageWhenNothingWasSettled() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);

        assertThat(repository.settledAmounts(AUGUST_FROM, AUGUST_TO, NONE).average()).isNull();
    }

    @Test
    void fraud_countsWhatWasNotPaidOnlyOnTheRejectedOnes() {
        // Fraud determined, rejected, backed by an expert assessment.
        tables.insertCase(1, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-08-10T10:00:00Z");
        tables.claimed(1, "400000.00");
        tables.fraudDetermined(1);
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", "FRAUD_CONFIRMED", null,
                "2026-08-03T10:00:00Z", "2026-08-08T10:00:00Z");
        // Fraud determined by the analyst alone, rejected.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-12T10:00:00Z");
        tables.claimed(2, "150000.00");
        tables.fraudDetermined(2);
        // Fraud determined but approved anyway: nothing saved.
        tables.insertCase(3, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(3, PENDING_REVIEW, APPROVED, "2026-08-14T10:00:00Z");
        tables.claimed(3, "999999.00");
        tables.fraudDetermined(3);
        // No fraud: only adds to the decided total.
        tables.insertCase(4, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(4, PENDING_REVIEW, APPROVED, "2026-08-15T10:00:00Z");

        FraudDetection fraud = repository.fraudDetection(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(fraud.decided()).isEqualTo(4);
        assertThat(fraud.fraudDetermined()).isEqualTo(3);
        assertThat(fraud.backedByExpert()).isEqualTo(1);
        assertThat(fraud.amountNotPaid()).isEqualByComparingTo("550000.00");
    }

    @Test
    void fraud_countsTheExpertBackingOncePerCase_evenWithTwoDerivations() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-08-10T10:00:00Z");
        tables.fraudDetermined(1);
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", "FRAUD_CONFIRMED", null,
                "2026-08-02T10:00:00Z", "2026-08-05T10:00:00Z");
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", "FRAUD_CONFIRMED", null,
                "2026-08-06T10:00:00Z", "2026-08-08T10:00:00Z");

        assertThat(repository.fraudDetection(AUGUST_FROM, AUGUST_TO, NONE).backedByExpert()).isEqualTo(1);
    }

    @Test
    void fastTrack_averagesEachSideSeparately() {
        // Fast Track: 2 days.
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, true, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-03T00:00:00Z");
        // The rest: 10 and 20 days, 15 on average.
        tables.insertCase(2, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, APPROVED, "2026-08-11T00:00:00Z");
        tables.insertCase(3, "2026-08-01T00:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(3, PENDING_REVIEW, REJECTED, "2026-08-21T00:00:00Z");

        assertThat(repository.fastTrackImpact(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(new FastTrackImpact(1, 48.0, 2, 360.0));
    }

    @Test
    void fastTrack_leavesTheAverageNullOnTheSideWithNothingDecided() {
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T00:00:00Z");

        assertThat(repository.fastTrackImpact(AUGUST_FROM, AUGUST_TO, NONE))
                .isEqualTo(new FastTrackImpact(0, null, 1, 96.0));
    }

    @Test
    void derivations_averageOnlyTheOnesThatCameBack_andKeepThePendingOnesVisible() {
        tables.insertCase(1, "2026-08-01T00:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        // Expert reports answered after 2 and 4 days, and a third still pending.
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", "FRAUD_DISCARDED", null,
                "2026-08-02T00:00:00Z", "2026-08-04T00:00:00Z");
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", "FRAUD_DISCARDED", null,
                "2026-08-05T00:00:00Z", "2026-08-09T00:00:00Z");
        tables.assessment(1, "ESTUDIO_LIQUIDADOR", null, null, "2026-08-10T00:00:00Z", null);
        // A repair shop that answered in one day.
        tables.assessment(1, "SERVICIO_TECNICO", null, "REPAIRED",
                "2026-08-12T00:00:00Z", "2026-08-13T00:00:00Z");

        List<DerivationTurnaround> derivations =
                repository.derivationTurnaround(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(derivations).containsExactly(
                new DerivationTurnaround("ESTUDIO_LIQUIDADOR", 3, 2, 72.0),
                new DerivationTurnaround("SERVICIO_TECNICO", 1, 1, 24.0));
        assertThat(derivations.getFirst().pending()).isEqualTo(1);
    }

    @Test
    void blockingRules_countCasesStoppedOnce_evenIfTheCaseWasReclassified() {
        tables.rule(14, "Vigencia de la póliza");
        tables.insertCase(1, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        // Reclassified: the same rule left two rows, but it is one blocked case.
        tables.ruleResult(1, 14L, "POLICY_IN_FORCE", "FAIL");
        tables.ruleResult(1, 14L, "POLICY_IN_FORCE", "FAIL");
        // No configurable rule row: named by its type.
        tables.ruleResult(1, null, "CLAIM_EXHAUSTS_COVERAGE", "FAIL");
        // A PASS blocks nothing.
        tables.ruleResult(1, 14L, "REPORT_DEADLINE", "PASS");
        // Nor does an advisory: its FAIL flags something, it stopped nothing.
        tables.ruleResult(1, null, "CLAIM_CAUSE_MATCH", "FAIL");
        tables.insertCase(2, "2026-08-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, LAURA, null);
        tables.ruleResult(2, 14L, "POLICY_IN_FORCE", "FAIL");

        assertThat(repository.countByBlockingRule(AUGUST_FROM, AUGUST_TO, NONE)).containsExactly(
                new MetricCount("Vigencia de la póliza", 2),
                new MetricCount("CLAIM_EXHAUSTS_COVERAGE", 1));
    }

    /** Filed 01/08, closed 05/08 in the given final status, with one model run behind it. */
    private void resolvedWithRecommendation(long caseId, String recommendation, long finalStatus) {
        tables.insertCase(caseId, "2026-08-01T10:00:00Z", finalStatus, ROBO_CELULARES, false, LAURA, null);
        tables.transition(caseId, PENDING_REVIEW, finalStatus, "2026-08-05T10:00:00Z");
        tables.recommendation(caseId, recommendation);
    }

    private void riskBand(long caseId, String band) {
        jdbcTemplate.update("UPDATE cases SET risk_band = ? WHERE id = ?", band, caseId);
    }
}
