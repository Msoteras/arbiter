package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTarget;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The dashboard, always scoped to the tenant the caller's JWT resolves to. */
@Service
@RequiredArgsConstructor
public class ClaimMetricsService {

    private final ClaimMetricsRepository claimMetricsRepository;
    private final LivePeriodMetrics livePeriodMetrics;
    private final StoredPeriodMetrics storedPeriodMetrics;
    private final DailyMetricsService dailyMetricsService;
    private final ReportPeriods reportPeriods;
    private final RulesServiceClient rulesServiceClient;
    private final Clock clock;

    /**
     * <b>One transaction for all the dashboard's queries</b>, not for atomicity but because each
     * connection acquisition sets the tenant search_path and each release resets it; one transaction
     * per query paid that round trip twenty times. Not read-only: the first request for a closed
     * period stores its days.
     *
     * <p>The resolution target is fetched <b>before</b> the first query on purpose: Hibernate acquires
     * the connection lazily, so the HTTP call to rules-service never holds one.
     *
     * <p>Only {@link StableMetrics} and the timeline come from the stored days. Everything else
     * describes the period's claims <b>as they are now</b> (or depends on a goal the referent can
     * change) and is always read live.
     *
     * @param range mutually exclusive with {@code from}/{@code to}; with all three absent, the last
     *              month
     */
    @Transactional
    public ClaimMetrics generate(MetricsRange range, LocalDate from, LocalDate to, MetricsFilter filter,
                                 ComparisonRequest comparisonRequest) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        ReportPeriod period = reportPeriods.main(range, from, to);
        ReportComparison comparison = reportPeriods.comparison(period, comparisonRequest);
        ZoneId zone = clock.getZone();
        ResolutionTarget target = rulesServiceClient.resolutionTarget();

        Instant start = period.start(zone);
        Instant end = period.end(zone);

        PeriodMetrics source = sourceFor(period);
        StableMetrics stable = source.stable(period, filter);
        TimelineGranularity granularity = TimelineGranularity.forPeriod(
                period.from(), period.to(), stable.summary().reportedCases());

        return new ClaimMetrics(
                period.from(),
                period.to(),
                clock.instant(),
                granularity,
                filter,
                claimMetricsRepository.intakeFunnel(start, end, filter),
                stable.summary(),
                comparison,
                sourceFor(comparison.period()).summary(comparison.period(), filter),
                stable.agreement(),
                resolutionTarget(target, start, end, filter),
                stable.legalDeadline(),
                stable.reopening(),
                stable.settled(),
                stable.fraud(),
                stable.fastTrack(),
                claimMetricsRepository.derivationTurnaround(start, end, filter),
                claimMetricsRepository.countByStatus(start, end, filter),
                stable.byBranch(),
                claimMetricsRepository.countByClassification(start, end, filter),
                claimMetricsRepository.countByRiskBand(start, end, filter),
                claimMetricsRepository.countByBlockingRule(start, end, filter),
                fillGaps(source.timeline(period, granularity, filter), period, granularity));
    }

    private PeriodMetrics sourceFor(ReportPeriod period) {
        return dailyMetricsService.covers(period) ? storedPeriodMetrics : livePeriodMetrics;
    }

    /**
     * Only queries when a target is set.
     *
     * @param target already fetched by {@link #generate} before the transaction touched the database
     */
    private ResolutionTarget resolutionTarget(
            ResolutionTarget target, Instant start, Instant end, MetricsFilter filter) {
        if (!target.enabled() || target.targetDays() == null) {
            return ResolutionTarget.UNSET;
        }
        return target.withExceeded(
                claimMetricsRepository.countDecidedOverTarget(start, end, target.targetDays(), filter));
    }

    /** The sources only return non-empty buckets; without the quiet ones the chart interpolates over them. */
    private static List<TimelinePoint> fillGaps(
            List<TimelinePoint> points, ReportPeriod period, TimelineGranularity granularity) {
        Map<LocalDate, TimelinePoint> found = points.stream()
                .collect(Collectors.toMap(TimelinePoint::bucket, Function.identity()));
        List<TimelinePoint> complete = new ArrayList<>();
        // The first bucket can predate the period: a period starting on a Wednesday is reported under
        // that week's Monday.
        LocalDate bucket = granularity.bucketOf(period.from());
        while (!bucket.isAfter(period.to())) {
            complete.add(found.getOrDefault(bucket, new TimelinePoint(bucket, 0, 0)));
            bucket = granularity.next(bucket);
        }
        return complete;
    }
}
