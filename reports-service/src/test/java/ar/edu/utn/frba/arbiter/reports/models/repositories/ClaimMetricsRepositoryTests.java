package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
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
 * Against real Postgres, because the aggregation is the whole feature: the {@code DISTINCT ON} that
 * picks the last closing, the Fast Track precedence, and the day buckets, which are only right if
 * the truncation happens in the insurer's time zone.
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
        // The dashboard is the only reader of the risk band, so the shared fixture doesn't carry
        // the column. Adding it here keeps CaseTables as the resolution report left it.
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
        // July: outside the window, and it should stay out of every figure.
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
        // "Requiere revisión manual" points nowhere — nothing to agree or disagree with.
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
        // Denunciado 01/08, aprobado 05/08: 4 días exactos.
        resolvedWithRecommendation(1, "LLM_RECOMIENDA_APROBAR", APPROVED);
        // Denunciado 01/08, rechazado 20/08: 19 días.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-20T10:00:00Z");
        // Caducado a los 25 días: nadie lo decidió, así que no está "fuera de objetivo".
        tables.insertCase(3, "2026-08-01T10:00:00Z", LAPSED, ROBO_CELULARES, false, null, null);
        tables.transition(3, PENDING_REVIEW, LAPSED, "2026-08-26T10:00:00Z");

        // Con 4 días de objetivo se pasa sólo el de 19; el de 4 lo cumplió justo, no lo excedió.
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 4, NONE)).isEqualTo(1);
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 3, NONE)).isEqualTo(2);
        assertThat(repository.countDecidedOverTarget(AUGUST_FROM, AUGUST_TO, 30, NONE)).isZero();
    }

    /**
     * La partición del tiempo. El fixture compartido sólo siembra cuatro estados, así que el de
     * "falta documentación" —el que frena el reloj— se agrega acá, igual que la banda de riesgo.
     */
    @Test
    void theSplit_discountsTheTimeSpentWaitingOnSomebodyOutside() {
        jdbcTemplate.update(
                "INSERT INTO case_status (id, name, is_final) VALUES (3, 'AWAITING_DOCUMENTATION', FALSE) "
                        + "ON CONFLICT (id) DO NOTHING");
        long awaitingDocs = 3;

        // Denunciado 01/08 00:00Z, aprobado 11/08 00:00Z: 10 días de reloj de pared. De esos, del
        // 03/08 al 09/08 estuvo esperando documentación del asegurado — 6 días que no son suyos.
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

    /** Un expediente que nunca esperó a nadie no descuenta nada: todo el tiempo es de la compañía. */
    @Test
    void theSplit_reportsZeroWaitingWhenNobodyWasEverWaitedOn() {
        tables.insertCase(1, "2026-08-01T00:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T00:00:00Z");

        ResolutionSplit split = repository.resolutionSplit(AUGUST_FROM, AUGUST_TO, NONE);

        assertThat(split.totalSeconds()).isEqualTo(4 * 86_400d);
        assertThat(split.waitingSeconds()).isZero();
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
