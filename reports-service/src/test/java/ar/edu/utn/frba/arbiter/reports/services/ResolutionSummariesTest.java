package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTimelinePoint;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.APPROVED_ROW_MINUTES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BUENOS_AIRES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.APPROVED_ROW_WAITING_MINUTES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.fastTrackRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
import static org.assertj.core.api.Assertions.assertThat;

/** The report's aggregate figures, over the rows it lists. */
class ResolutionSummariesTest {

    @Test
    void countsTheCases_andAveragesHowLongTheyTook() {
        List<ResolutionReportRow> rows = List.of(approvedRow(1), fastTrackRow(2));

        ResolutionSummary summary = ResolutionSummaries.of(rows);

        assertThat(summary.totalCases()).isEqualTo(2);
        assertThat(summary.decidedCases()).isEqualTo(2);
        assertThat(summary.averageMinutes()).isEqualTo((APPROVED_ROW_MINUTES + 120) / 2.0);
        assertThat(summary.averageWaitingMinutes()).isEqualTo(APPROVED_ROW_WAITING_MINUTES / 2.0);
    }

    /** Lapsed cases are left out of the average, as in the dashboard: one of 790 days would swamp it. */
    @Test
    void theAverages_leaveTheLapsedCasesOut_likeTheDashboardDoes() {
        ResolutionSummary summary = ResolutionSummaries.of(List.of(approvedRow(1), lapsedRow(2)));

        assertThat(summary.totalCases()).isEqualTo(2);
        assertThat(summary.decidedCases()).isEqualTo(1);
        assertThat(summary.averageMinutes()).isEqualTo(APPROVED_ROW_MINUTES);
        assertThat(summary.averageWaitingMinutes()).isEqualTo(APPROVED_ROW_WAITING_MINUTES);
    }

    /** Nobody decided anything: the average is unknown, and the rows are still there to be listed. */
    @Test
    void withOnlyLapsedCases_thereIsNoAverage() {
        ResolutionSummary summary = ResolutionSummaries.of(List.of(lapsedRow(1)));

        assertThat(summary.totalCases()).isEqualTo(1);
        assertThat(summary.decidedCases()).isZero();
        assertThat(summary.averageMinutes()).isNull();
        assertThat(summary.averageWaitingMinutes()).isNull();
    }

    @Test
    void fastTrackShare_isOverTheCasesClosedInThePeriod() {
        ResolutionSummary summary =
                ResolutionSummaries.of(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3), approvedRow(4)));

        assertThat(summary.fastTrackCases()).isEqualTo(1);
        assertThat(summary.fastTrackRate()).isEqualTo(0.25);
    }

    /** Status buckets carry the enum literal; translation belongs to the frontend and {@code ReportLabels}. */
    @Test
    void distributions_useEnumLiterals_andPutTheBusiestBucketFirst() {
        ResolutionSummary summary =
                ResolutionSummaries.of(List.of(lapsedRow(1), approvedRow(2), approvedRow(3)));

        assertThat(summary.byStatus()).containsExactly(
                new MetricCount(CaseStatus.APPROVED.name(), 2),
                new MetricCount(CaseStatus.LAPSED.name(), 1));
        assertThat(summary.byClaimCause()).containsExactly(
                new MetricCount("Robo en vía pública", 2),
                new MetricCount("Hurto", 1));
    }

    /** Same counts must render in the same order every time, or the file changes between exports. */
    @Test
    void tiedBuckets_breakByLabel() {
        ResolutionSummary summary = ResolutionSummaries.of(List.of(approvedRow(1), lapsedRow(2)));

        assertThat(summary.byClaimCause()).extracting(MetricCount::label)
                .containsExactly("Hurto", "Robo en vía pública");
    }

    /** An average of nothing is null, not zero: a zero would read as instant resolution. */
    @Test
    void withNoResolvedCases_theFiguresAreAbsentRatherThanZero() {
        ResolutionSummary summary = ResolutionSummaries.of(List.of());

        assertThat(summary).isEqualTo(ResolutionSummary.EMPTY);
        assertThat(summary.averageMinutes()).isNull();
        assertThat(summary.averageWaitingMinutes()).isNull();
        assertThat(summary.fastTrackRate()).isNull();
    }

    private static final LocalDate AUGUST_FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUGUST_TO = LocalDate.of(2026, 8, 31);

    /** Bucketed by closing date in the insurer's zone, empty buckets included. */
    @Test
    void timeline_spreadsTheRowsOverEveryBucketOfThePeriod() {
        List<ResolutionReportRow> rows = List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3));

        List<ResolutionTimelinePoint> timeline = ResolutionSummaries.timeline(
                rows, AUGUST_FROM, AUGUST_TO, BUENOS_AIRES, TimelineGranularity.WEEK);

        // Weeks truncate to Monday, the same as Postgres' date_trunc: 01/08/2026 is a Saturday, so
        // the first bucket of August starts in July.
        assertThat(timeline).extracting(ResolutionTimelinePoint::bucket)
                .containsExactly(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 3),
                        LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17),
                        LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 31));
        assertThat(timeline).extracting(ResolutionTimelinePoint::resolved)
                .containsExactly(1L, 2L, 0L, 0L, 0L, 0L);
    }

    /** The count includes the lapsed case, the average doesn't: nobody decided it. */
    @Test
    void timeline_averagesTheDecidedOnes_andLeavesTheRestUnknown() {
        List<ResolutionReportRow> rows = List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3));

        List<ResolutionTimelinePoint> timeline = ResolutionSummaries.timeline(
                rows, AUGUST_FROM, AUGUST_TO, BUENOS_AIRES, TimelineGranularity.WEEK);

        assertThat(timeline.get(0).resolved()).isEqualTo(1);
        assertThat(timeline.get(0).decided()).isZero();
        assertThat(timeline.get(0).averageMinutes()).isNull();
        assertThat(timeline.get(1).decided()).isEqualTo(2);
        assertThat(timeline.get(1).averageMinutes()).isEqualTo((APPROVED_ROW_MINUTES + 120) / 2.0);
    }
}
