package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.APPROVED_ROW_MINUTES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.APPROVED_ROW_WAITING_MINUTES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.fastTrackRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
import static org.assertj.core.api.Assertions.assertThat;

/** The four figures H0019 asks the report to show, over the rows the report lists. */
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

    /**
     * Same population the dashboard averages over: a lapsed case wasn't resolved by anybody, it
     * measured the insured's silence, and one of them (790 days here) swamps the average of a month.
     */
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

    /**
     * Status buckets carry the enum literal: translating them is the frontend's job for the preview
     * and {@code ReportLabels}' job for the exports, never the service's.
     */
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

    /** An average of nothing is unknown, not zero — and a zero would read as "instant resolution". */
    @Test
    void withNoResolvedCases_theFiguresAreAbsentRatherThanZero() {
        ResolutionSummary summary = ResolutionSummaries.of(List.of());

        assertThat(summary).isEqualTo(ResolutionSummary.EMPTY);
        assertThat(summary.averageMinutes()).isNull();
        assertThat(summary.averageWaitingMinutes()).isNull();
        assertThat(summary.fastTrackRate()).isNull();
    }
}
