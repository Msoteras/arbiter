package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTarget;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the dashboard's figures mean: which claims each one counts, and which it deliberately
 * leaves out. The repository is mocked — that the SQL adds up is
 * {@code ClaimMetricsRepositoryTests}' job.
 */
@ExtendWith(MockitoExtension.class)
// The happy paths stub every read; the validation ones throw before any of them runs.
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimMetricsServiceTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Midday of 11/09/2026 in Buenos Aires, so "today" is unambiguous. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T15:00:00Z"), BUENOS_AIRES);

    @Mock
    private ClaimMetricsRepository claimMetricsRepository;

    @Mock
    private RulesServiceClient rulesServiceClient;

    private ClaimMetricsService claimMetricsService;

    @BeforeEach
    void setUp() {
        claimMetricsService = new ClaimMetricsService(claimMetricsRepository, rulesServiceClient, CLOCK);
        TenantContext.set("arbiter_bbva");
        stubEmpty();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubEmpty() {
        given(claimMetricsRepository.intakeTotals(any(), any(), any())).willReturn(new IntakeTotals(0, 0));
        given(claimMetricsRepository.resolvedTotals(any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.intakeFunnel(any(), any(), any())).willReturn(IntakeFunnel.EMPTY);
        given(claimMetricsRepository.recommendationAgreement(any(), any(), any()))
                .willReturn(RecommendationAgreement.EMPTY);
        given(claimMetricsRepository.countByStatus(any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.countByBranch(any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.countByClassification(any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.countByRiskBand(any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.timeline(any(), any(), any(), any(), any())).willReturn(List.of());
        given(claimMetricsRepository.resolutionSplit(any(), any(), any()))
                .willReturn(ResolutionSplit.NONE);
        given(rulesServiceClient.resolutionTarget()).willReturn(ResolutionTarget.UNSET);
    }

    @Test
    void withoutTenant_isRejected() {
        TenantContext.clear();

        assertThatThrownBy(() -> claimMetricsService.generate(MetricsRange.WEEK, null, null, MetricsFilter.NONE))
                .isInstanceOf(TenantNotResolvedException.class);
    }

    @Test
    void withNoPeriod_defaultsToTheLastMonth() {
        ClaimMetrics metrics = claimMetricsService.generate(null, null, null, MetricsFilter.NONE);

        assertThat(metrics.to()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(metrics.from()).isEqualTo(LocalDate.of(2026, 8, 13));
    }

    @Test
    void aRange_isAWindowEndingToday_todayIncluded() {
        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.WEEK, null, null, MetricsFilter.NONE);

        assertThat(metrics.from()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(metrics.to()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void aRangeTogetherWithACustomPeriod_isRejected() {
        assertThatThrownBy(() -> claimMetricsService.generate(
                MetricsRange.WEEK, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), MetricsFilter.NONE))
                .isInstanceOf(InvalidReportPeriodException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void halfACustomPeriod_isRejected() {
        assertThatThrownBy(() -> claimMetricsService.generate(null, LocalDate.of(2026, 8, 1), null, MetricsFilter.NONE))
                .isInstanceOf(InvalidReportPeriodException.class)
                .hasMessageContaining("both");
    }

    @Test
    void aPeriodThatRunsBackwards_isRejected() {
        assertThatThrownBy(() -> claimMetricsService.generate(
                null, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1), MetricsFilter.NONE))
                .isInstanceOf(InvalidReportPeriodException.class)
                .hasMessageContaining("is after");
    }

    @Test
    void aPeriodLongerThanAYear_isRejected() {
        assertThatThrownBy(() -> claimMetricsService.generate(
                null, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 11), MetricsFilter.NONE))
                .isInstanceOf(InvalidReportPeriodException.class)
                .hasMessageContaining("366");
    }

    @Test
    void approvalRate_ignoresLapsedCases() {
        given(claimMetricsRepository.resolvedTotals(any(), any(), any())).willReturn(List.of(
                new ResolvedTotals("APPROVED", 6, hours(24)),
                new ResolvedTotals("REJECTED", 2, hours(48)),
                // Nobody decided this one; it ran out of time. Counting it would report 60%
                // approval where the analysts actually approved 75% of what they decided.
                new ResolvedTotals("LAPSED", 2, hours(13_000))));

        MetricsSummary summary = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).summary();

        assertThat(summary.resolvedCases()).isEqualTo(10);
        assertThat(summary.approvedCases()).isEqualTo(6);
        assertThat(summary.lapsedCases()).isEqualTo(2);
        assertThat(summary.approvalRate()).isEqualTo(0.75);
        assertThat(summary.rejectionRate()).isEqualTo(0.25);
    }

    @Test
    void averageResolution_weighsEachStatusAndLeavesLapsedOut() {
        given(claimMetricsRepository.resolvedTotals(any(), any(), any())).willReturn(List.of(
                new ResolvedTotals("APPROVED", 3, hours(10)),
                new ResolvedTotals("REJECTED", 1, hours(30)),
                new ResolvedTotals("LAPSED", 1, hours(13_000))));

        MetricsSummary summary = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).summary();

        // (3*10 + 1*30) / 4 = 15. Averaging the two averages would have said 20, and letting the
        // lapsed one in, over 2600.
        assertThat(summary.averageResolutionHours()).isEqualTo(15.0);
    }

    @Test
    void fastTrackRate_readsAgainstTheClaimsFiled() {
        given(claimMetricsRepository.intakeTotals(any(), any(), any())).willReturn(new IntakeTotals(40, 10));

        MetricsSummary summary = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).summary();

        assertThat(summary.reportedCases()).isEqualTo(40);
        assertThat(summary.fastTrackRate()).isEqualTo(0.25);
    }

    @Test
    void withNothingToDivide_ratesAreUnknownRatherThanZero() {
        MetricsSummary summary = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).summary();

        assertThat(summary.approvalRate()).isNull();
        assertThat(summary.rejectionRate()).isNull();
        assertThat(summary.fastTrackRate()).isNull();
        assertThat(summary.averageResolutionHours()).isNull();
    }

    /**
     * The comparison window is the stretch of equal length ending the day before, not "the previous
     * calendar month": a custom 17-day period has to be compared against 17 days or the delta is
     * measuring the calendar instead of the operation.
     */
    /**
     * El procedimiento de la compañía dice que pedir documentación o derivar a un perito interrumpe
     * el plazo. El tablero parte el promedio en las dos mitades para que el referente vea cuánto de
     * la demora es suya.
     */
    @Test
    void theAverage_separatesTheInsurersOwnTimeFromWaitingOnThirdParties() {
        given(claimMetricsRepository.resolvedTotals(any(), any(), any())).willReturn(List.of(
                new ResolvedTotals("APPROVED", 4, hours(30 * 24))));
        given(claimMetricsRepository.resolutionSplit(any(), any(), any()))
                .willReturn(new ResolutionSplit(hours(30 * 24), hours(18 * 24)));

        MetricsSummary summary = claimMetricsService.generate(
                MetricsRange.MONTH, null, null, MetricsFilter.NONE).summary();

        // 30 días de reloj de pared, de los cuales 18 fueron esperando a alguien de afuera.
        assertThat(summary.averageResolutionHours()).isEqualTo(30 * 24.0);
        assertThat(summary.averageWaitingHours()).isEqualTo(18 * 24.0);
    }

    @Test
    void previousSummary_readsTheEqualStretchEndingTheDayBefore() {
        // The default month runs 13/08 → 11/09, so the one before it is 14/07 → 12/08.
        Instant previousStart = LocalDate.of(2026, 7, 14).atStartOfDay(BUENOS_AIRES).toInstant();
        Instant previousEnd = LocalDate.of(2026, 8, 13).atStartOfDay(BUENOS_AIRES).toInstant();
        given(claimMetricsRepository.intakeTotals(eq(previousStart), eq(previousEnd), any()))
                .willReturn(new IntakeTotals(10, 2));
        Instant start = LocalDate.of(2026, 8, 13).atStartOfDay(BUENOS_AIRES).toInstant();
        Instant end = LocalDate.of(2026, 9, 12).atStartOfDay(BUENOS_AIRES).toInstant();
        given(claimMetricsRepository.intakeTotals(eq(start), eq(end), any()))
                .willReturn(new IntakeTotals(12, 3));

        ClaimMetrics metrics = claimMetricsService.generate(null, null, null, MetricsFilter.NONE);

        assertThat(metrics.summary().reportedCases()).isEqualTo(12);
        assertThat(metrics.previousSummary().reportedCases()).isEqualTo(10);
    }

    /**
     * The insurer of the fixtures files about a dozen claims a month. Read day by day that is a row
     * of thirty points, almost all zero — so the timeline steps up to weeks on its own.
     */
    @Test
    void aQuietMonth_isGroupedByWeekInsteadOfByDay() {
        given(claimMetricsRepository.intakeTotals(any(), any(), any())).willReturn(new IntakeTotals(12, 3));

        assertThat(claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).granularity())
                .isEqualTo(TimelineGranularity.WEEK);
    }

    @Test
    void aBusyMonth_keepsTheDailyDetail() {
        given(claimMetricsRepository.intakeTotals(any(), any(), any())).willReturn(new IntakeTotals(300, 10));

        assertThat(claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).granularity())
                .isEqualTo(TimelineGranularity.DAY);
    }

    @Test
    void theFilter_travelsToEveryReadAndComesBackInTheResponse() {
        MetricsFilter filter = new MetricsFilter(7L, 3L);

        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.MONTH, null, null, filter);

        assertThat(metrics.filter()).isEqualTo(filter);
        verify(claimMetricsRepository, atLeastOnce()).countByStatus(any(), any(), eq(filter));
        verify(claimMetricsRepository, atLeastOnce()).intakeFunnel(any(), any(), eq(filter));
        // Also on the comparison window, or the delta would compare a filtered period against an
        // unfiltered one and invent a swing.
        verify(claimMetricsRepository, times(2)).intakeTotals(any(), any(), eq(filter));
    }

    @Test
    void withoutATarget_theDashboardDoesNotEvenAskHowManyExceededIt() {
        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE);

        assertThat(metrics.resolutionTarget()).isEqualTo(ResolutionTarget.UNSET);
        verify(claimMetricsRepository, never()).countDecidedOverTarget(any(), any(), anyInt(), any());
    }

    @Test
    void withATarget_countsHowManyDecisionsRanPastIt() {
        given(rulesServiceClient.resolutionTarget()).willReturn(new ResolutionTarget(true, 21, 0));
        given(claimMetricsRepository.countDecidedOverTarget(any(), any(), eq(21), any())).willReturn(3L);

        ResolutionTarget target =
                claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE).resolutionTarget();

        assertThat(target).isEqualTo(new ResolutionTarget(true, 21, 3));
    }

    /**
     * Un rules-service caído devuelve el objetivo sin fijar, no una excepción: el tablero pierde la
     * comparación y conserva todo lo demás, que es lo que el referente vino a ver.
     */
    @Test
    void aTargetThatCouldNotBeRead_leavesTheRestOfTheDashboardStanding() {
        given(claimMetricsRepository.resolutionSplit(any(), any(), any()))
                .willReturn(ResolutionSplit.NONE);
        given(rulesServiceClient.resolutionTarget()).willReturn(ResolutionTarget.UNSET);
        given(claimMetricsRepository.intakeTotals(any(), any(), any())).willReturn(new IntakeTotals(12, 3));

        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.MONTH, null, null, MetricsFilter.NONE);

        assertThat(metrics.resolutionTarget().enabled()).isFalse();
        assertThat(metrics.summary().reportedCases()).isEqualTo(12);
    }

    @Test
    void theTimeline_hasAPointPerBucketEvenWhereNothingHappened() {
        given(claimMetricsRepository.timeline(any(), any(), any(), any(), any())).willReturn(List.of(
                new TimelinePoint(LocalDate.of(2026, 9, 7), 3, 1)));

        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.WEEK, null, null, MetricsFilter.NONE);

        assertThat(metrics.granularity()).isEqualTo(TimelineGranularity.DAY);
        assertThat(metrics.timeline()).hasSize(7);
        assertThat(metrics.timeline().getFirst().bucket()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(metrics.timeline().getLast().bucket()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(metrics.timeline())
                .contains(new TimelinePoint(LocalDate.of(2026, 9, 7), 3, 1))
                .contains(new TimelinePoint(LocalDate.of(2026, 9, 8), 0, 0));
    }

    @Test
    void aQuarter_readsWeekByWeek_startingOnTheMondayOfTheFirstWeek() {
        ClaimMetrics metrics = claimMetricsService.generate(MetricsRange.QUARTER, null, null, MetricsFilter.NONE);

        assertThat(metrics.granularity()).isEqualTo(TimelineGranularity.WEEK);
        // The period opens on Sunday 14/06; Postgres buckets weeks from Monday, so the first point
        // is the 08/06 that contains it.
        assertThat(metrics.from()).isEqualTo(LocalDate.of(2026, 6, 14));
        assertThat(metrics.timeline().getFirst().bucket()).isEqualTo(LocalDate.of(2026, 6, 8));
    }

    private static double hours(int hours) {
        return hours * 3600.0;
    }
}
