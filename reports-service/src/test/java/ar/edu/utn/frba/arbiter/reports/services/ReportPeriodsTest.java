package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.ComparisonMode;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What each comparison resolves to, and which periods count as closed. Today is 11/09/2026. */
class ReportPeriodsTest {

    private static final ReportPeriod AUGUST = new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

    private final ReportPeriods reportPeriods = new ReportPeriods(CLOCK);

    @Test
    void withNoMode_comparesAgainstTheEqualStretchRightBefore() {
        ReportComparison comparison = reportPeriods.comparison(AUGUST, ComparisonRequest.DEFAULT);

        assertThat(comparison).isEqualTo(new ReportComparison(
                ComparisonMode.PREVIOUS_PERIOD, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));
    }

    /** Equal length, not "the calendar month before": 17 days compare against 17 days. */
    @Test
    void thePreviousPeriod_isAsLongAsThePeriod() {
        ReportPeriod seventeenDays = new ReportPeriod(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 26));

        ReportComparison comparison = reportPeriods.comparison(seventeenDays, ComparisonRequest.DEFAULT);

        assertThat(comparison.period().days()).isEqualTo(17);
        assertThat(comparison.to()).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    void lastYear_isTheSameDatesAYearEarlier() {
        ReportComparison comparison = reportPeriods.comparison(AUGUST,
                new ComparisonRequest(ComparisonMode.SAME_PERIOD_LAST_YEAR, null, null));

        assertThat(comparison.from()).isEqualTo(LocalDate.of(2025, 8, 1));
        assertThat(comparison.to()).isEqualTo(LocalDate.of(2025, 8, 31));
    }

    @Test
    void lastYear_ofALeapDay_fallsBackToTheTwentyEighth() {
        ReportPeriod leapFebruary = new ReportPeriod(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29));

        ReportComparison comparison = reportPeriods.comparison(leapFebruary,
                new ComparisonRequest(ComparisonMode.SAME_PERIOD_LAST_YEAR, null, null));

        assertThat(comparison.to()).isEqualTo(LocalDate.of(2023, 2, 28));
    }

    @Test
    void aCustomComparison_isTakenAsGiven_whateverItsLength() {
        ReportComparison comparison = reportPeriods.comparison(AUGUST, new ComparisonRequest(
                ComparisonMode.CUSTOM, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)));

        assertThat(comparison.from()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(comparison.to()).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    @Test
    void aCustomComparison_isValidatedLikeThePeriod() {
        assertThatThrownBy(() -> reportPeriods.comparison(AUGUST,
                new ComparisonRequest(ComparisonMode.CUSTOM, LocalDate.of(2025, 1, 1), null)))
                .isInstanceOf(InvalidReportPeriodException.class);
        assertThatThrownBy(() -> reportPeriods.comparison(AUGUST, new ComparisonRequest(
                ComparisonMode.CUSTOM, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 1, 1))))
                .isInstanceOf(InvalidReportPeriodException.class);
        assertThatThrownBy(() -> reportPeriods.comparison(AUGUST, new ComparisonRequest(
                ComparisonMode.CUSTOM, LocalDate.of(2023, 1, 1), LocalDate.of(2025, 1, 1))))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void customDatesWithoutTheCustomMode_areRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> reportPeriods.comparison(AUGUST, new ComparisonRequest(
                ComparisonMode.PREVIOUS_PERIOD, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31))))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void aPeriodEndingYesterday_isClosed_andOneThatIncludesTodayIsNot() {
        LocalDate today = LocalDate.of(2026, 9, 11);

        assertThat(reportPeriods.isClosed(new ReportPeriod(today.minusDays(30), today.minusDays(1)))).isTrue();
        assertThat(reportPeriods.isClosed(new ReportPeriod(today.minusDays(30), today))).isFalse();
        assertThat(reportPeriods.isClosed(new ReportPeriod(today, today.plusDays(5)))).isFalse();
    }
}
