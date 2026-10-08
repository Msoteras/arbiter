package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyIntake;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/** How a period's rates and averages come out of its days' sums and counts; the repository is mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoredPeriodMetricsTest {

    private static final ReportPeriod AUGUST = new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    private static final LocalDate AUG_3 = LocalDate.of(2026, 8, 3);
    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    private static final double HOUR = 3600;

    @Mock
    private DailyMetricsRepository dailyMetricsRepository;

    private StoredPeriodMetrics storedPeriodMetrics;

    @BeforeEach
    void setUp() {
        storedPeriodMetrics = new StoredPeriodMetrics(dailyMetricsRepository);
        given(dailyMetricsRepository.intake(any(), any())).willReturn(List.of());
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of());
        given(dailyMetricsRepository.settled(any(), any())).willReturn(SettledAmounts.NONE);
        given(dailyMetricsRepository.reportedByBranch(any(), any())).willReturn(List.of());
    }

    /**
     * One case in 10 h on the 3rd and nine in 100 h each on the 20th. The period's average is 91 h; the
     * average of the two days' averages would be 55 h, which is why days store sums.
     */
    @Test
    void theAverage_isTheSumOverTheCount_notAnAverageOfDailyAverages() {
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                resolved(AUG_3, "APPROVED", 1, 10 * HOUR),
                resolved(AUG_20, "APPROVED", 9, 900 * HOUR)));

        MetricsSummary summary = storedPeriodMetrics.summary(AUGUST, MetricsFilter.NONE);

        assertThat(summary.averageResolutionHours()).isEqualTo(91.0);
    }

    @Test
    void rates_areWorkedOutFromTheCountsOfEveryDay() {
        given(dailyMetricsRepository.intake(any(), any())).willReturn(List.of(
                new DailyIntake(AUG_3, 2, 2), new DailyIntake(AUG_20, 8, 0)));
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                resolved(AUG_3, "APPROVED", 3, 0),
                resolved(AUG_20, "REJECTED", 1, 0),
                resolved(AUG_20, "LAPSED", 4, 0)));

        MetricsSummary summary = storedPeriodMetrics.summary(AUGUST, MetricsFilter.NONE);

        assertThat(summary.fastTrackRate()).isEqualTo(0.2);
        // Over the decided ones: the four lapsed claims are in neither side of the fraction.
        assertThat(summary.approvalRate()).isEqualTo(0.75);
        assertThat(summary.resolvedCases()).isEqualTo(8);
        assertThat(summary.lapsedCases()).isEqualTo(4);
    }

    @Test
    void lapsedCases_stayOutOfTheAverageAndOfTheLegalDeadline() {
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                row(AUG_3, "APPROVED", 2, 40 * HOUR, 8 * HOUR, 0, 0, 1, 0, BigDecimal.ZERO),
                row(AUG_20, "LAPSED", 1, 9000 * HOUR, 0, 0, 0, 0, 0, BigDecimal.ZERO)));

        StableMetrics stable = storedPeriodMetrics.stable(AUGUST, MetricsFilter.NONE);

        assertThat(stable.summary().averageResolutionHours()).isEqualTo(20.0);
        assertThat(stable.summary().averageWaitingHours()).isEqualTo(4.0);
        assertThat(stable.legalDeadline().decided()).isEqualTo(2);
        assertThat(stable.legalDeadline().rate()).isEqualTo(0.5);
        assertThat(stable.reopening().resolved()).isEqualTo(3);
    }

    @Test
    void fastTrack_isSplitFromTheRestByItsOwnSumOfTime() {
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                row(AUG_3, "APPROVED", 4, 124 * HOUR, 0, 2, 4 * HOUR, 4, 0, BigDecimal.ZERO)));

        StableMetrics stable = storedPeriodMetrics.stable(AUGUST, MetricsFilter.NONE);

        assertThat(stable.fastTrack().fastTrackDecided()).isEqualTo(2);
        assertThat(stable.fastTrack().fastTrackHours()).isEqualTo(2.0);
        assertThat(stable.fastTrack().standardDecided()).isEqualTo(2);
        assertThat(stable.fastTrack().standardHours()).isEqualTo(60.0);
    }

    /** One approved despite fraud was paid anyway, so only the rejected ones count as not paid. */
    @Test
    void theAmountNotPaid_onlyCountsTheRejectedCases() {
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                row(AUG_3, "REJECTED", 2, 0, 0, 0, 0, 2, 1, new BigDecimal("340000.00")),
                row(AUG_3, "APPROVED", 5, 0, 0, 0, 0, 5, 1, new BigDecimal("90000.00"))));

        StableMetrics stable = storedPeriodMetrics.stable(AUGUST, MetricsFilter.NONE);

        assertThat(stable.fraud().decided()).isEqualTo(7);
        assertThat(stable.fraud().fraudDetermined()).isEqualTo(2);
        assertThat(stable.fraud().amountNotPaid()).isEqualByComparingTo("340000.00");
    }

    @Test
    void withNothingStoredForThePeriod_ratesAreUnknownRatherThanZero() {
        StableMetrics stable = storedPeriodMetrics.stable(AUGUST, MetricsFilter.NONE);

        assertThat(stable.summary().approvalRate()).isNull();
        assertThat(stable.summary().averageResolutionHours()).isNull();
        assertThat(stable.legalDeadline().rate()).isNull();
        assertThat(stable.fastTrack().fastTrackHours()).isNull();
    }

    /** Weeks start on Monday, like Postgres' date_trunc: the 3rd and the 5th of August 2026 share one. */
    @Test
    void theTimeline_addsTheDaysOfEachBucket() {
        given(dailyMetricsRepository.intake(any(), any())).willReturn(List.of(
                new DailyIntake(AUG_3, 2, 0), new DailyIntake(LocalDate.of(2026, 8, 5), 3, 0)));
        given(dailyMetricsRepository.resolution(any(), any())).willReturn(List.of(
                resolved(AUG_20, "APPROVED", 4, 0), resolved(AUG_20, "LAPSED", 1, 0)));

        List<TimelinePoint> timeline =
                storedPeriodMetrics.timeline(AUGUST, TimelineGranularity.WEEK, MetricsFilter.NONE);

        assertThat(timeline).containsExactly(
                new TimelinePoint(AUG_3, 5, 0),
                new TimelinePoint(LocalDate.of(2026, 8, 17), 0, 5));
    }

    private static DailyResolution resolved(LocalDate day, String status, long cases, double totalSeconds) {
        return row(day, status, cases, totalSeconds, 0, 0, 0, cases, 0, BigDecimal.ZERO);
    }

    private static DailyResolution row(LocalDate day, String status, long cases, double totalSeconds,
                                       double waitingSeconds, long fastTrack, double fastTrackSeconds,
                                       long onTime, long fraudDetermined, BigDecimal fraudClaimedAmount) {
        return new DailyResolution(day, status, cases, totalSeconds, waitingSeconds, fastTrack,
                fastTrackSeconds, onTime, 0, 0, 0, fraudDetermined, 0, fraudClaimedAmount);
    }
}
