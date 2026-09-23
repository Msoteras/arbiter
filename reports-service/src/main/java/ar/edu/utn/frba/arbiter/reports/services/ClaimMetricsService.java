package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTarget;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The dashboard, always scoped to the tenant the caller's JWT resolves to. */
@Service
@RequiredArgsConstructor
public class ClaimMetricsService {

    /** Not a business rule: a guard on how much one request can scan. */
    static final int MAX_PERIOD_DAYS = 366;

    private static final MetricsRange DEFAULT_RANGE = MetricsRange.MONTH;

    private final ClaimMetricsRepository claimMetricsRepository;
    private final RulesServiceClient rulesServiceClient;
    private final Clock clock;

    /**
     * <b>One transaction for all the dashboard's queries</b>, not for atomicity but because each
     * connection acquisition sets the tenant search_path and each release resets it; one transaction
     * per query paid that round trip twenty times.
     *
     * <p>The resolution target is fetched <b>before</b> the first query on purpose: Hibernate acquires
     * the connection lazily, so the HTTP call to rules-service never holds one.
     *
     * @param range mutually exclusive with {@code from}/{@code to}; with all three absent,
     *              {@link #DEFAULT_RANGE}
     */
    @Transactional(readOnly = true)
    public ClaimMetrics generate(MetricsRange range, LocalDate from, LocalDate to, MetricsFilter filter) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        Period period = resolvePeriod(range, from, to);
        ZoneId zone = clock.getZone();
        ResolutionTarget target = rulesServiceClient.resolutionTarget();

        // Whole calendar days in the insurer's time zone, both ends included: the upper bound is the
        // next midnight, exclusive.
        Instant start = period.from().atStartOfDay(zone).toInstant();
        Instant end = period.to().plusDays(1).atStartOfDay(zone).toInstant();

        IntakeTotals intake = claimMetricsRepository.intakeTotals(start, end, filter);
        List<ResolvedTotals> resolved = claimMetricsRepository.resolvedTotals(start, end, filter);
        ResolutionSplit split = claimMetricsRepository.resolutionSplit(start, end, filter);
        TimelineGranularity granularity =
                TimelineGranularity.forPeriod(period.from(), period.to(), intake.reported());

        return new ClaimMetrics(
                period.from(),
                period.to(),
                clock.instant(),
                granularity,
                filter,
                claimMetricsRepository.intakeFunnel(start, end, filter),
                summarize(intake, resolved, split),
                previousSummary(period, zone, filter),
                claimMetricsRepository.recommendationAgreement(start, end, filter),
                resolutionTarget(target, start, end, filter),
                claimMetricsRepository.legalDeadlineCompliance(start, end, zone, filter),
                claimMetricsRepository.reopeningRate(start, end, filter),
                claimMetricsRepository.settledAmounts(start, end, filter),
                claimMetricsRepository.fraudDetection(start, end, filter),
                claimMetricsRepository.fastTrackImpact(start, end, filter),
                claimMetricsRepository.derivationTurnaround(start, end, filter),
                claimMetricsRepository.countByStatus(start, end, filter),
                claimMetricsRepository.countByBranch(start, end, filter),
                claimMetricsRepository.countByClassification(start, end, filter),
                claimMetricsRepository.countByRiskBand(start, end, filter),
                claimMetricsRepository.countByBlockingRule(start, end, filter),
                fillGaps(claimMetricsRepository.timeline(start, end, granularity, zone, filter),
                        period, granularity));
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

    private MetricsSummary previousSummary(Period period, ZoneId zone, MetricsFilter filter) {
        PreviousPeriod previous = PreviousPeriod.immediatelyBefore(period.from(), period.to());
        Instant start = previous.from().atStartOfDay(zone).toInstant();
        Instant end = previous.to().plusDays(1).atStartOfDay(zone).toInstant();
        // No waiting split: the header deltas only use the total, and the split costs an extra
        // windowed query for a number that isn't shown.
        return summarize(
                claimMetricsRepository.intakeTotals(start, end, filter),
                claimMetricsRepository.resolvedTotals(start, end, filter),
                ResolutionSplit.NONE);
    }

    private record Period(LocalDate from, LocalDate to) {}

    private Period resolvePeriod(MetricsRange range, LocalDate from, LocalDate to) {
        boolean custom = from != null || to != null;
        if (range != null && custom) {
            throw new InvalidReportPeriodException(
                    "Use either 'range' or 'from'/'to', not both");
        }
        if (!custom) {
            MetricsRange effective = range == null ? DEFAULT_RANGE : range;
            return new Period(effective.from(clock), effective.to(clock));
        }
        if (from == null || to == null) {
            throw new InvalidReportPeriodException("A custom period needs both 'from' and 'to'");
        }
        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException("'from' (%s) is after 'to' (%s)".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidReportPeriodException(
                    "The period can't be longer than %d days".formatted(MAX_PERIOD_DAYS));
        }
        return new Period(from, to);
    }

    private static MetricsSummary summarize(
            IntakeTotals intake, List<ResolvedTotals> resolved, ResolutionSplit split) {
        Map<String, ResolvedTotals> byStatus = resolved.stream()
                .collect(Collectors.toMap(ResolvedTotals::status, Function.identity()));
        long approved = countOf(byStatus, CaseStatus.APPROVED);
        long rejected = countOf(byStatus, CaseStatus.REJECTED);
        long lapsed = countOf(byStatus, CaseStatus.LAPSED);
        long resolvedCases = resolved.stream().mapToLong(ResolvedTotals::count).sum();
        // Decided, not resolved: a lapsed claim closed without anyone deciding it.
        long decided = approved + rejected;

        return new MetricsSummary(
                intake.reported(),
                intake.fastTrack(),
                resolvedCases,
                approved,
                rejected,
                lapsed,
                rate(approved, decided),
                rate(rejected, decided),
                rate(intake.fastTrack(), intake.reported()),
                averageHours(byStatus),
                split.waitingSeconds() == null ? null : split.waitingSeconds() / 3600);
    }

    private static long countOf(Map<String, ResolvedTotals> byStatus, CaseStatus status) {
        ResolvedTotals totals = byStatus.get(status.name());
        return totals == null ? 0 : totals.count();
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    /** Weighted by count: averaging the per-status averages would let one rejection weigh as much as fifty approvals. */
    private static Double averageHours(Map<String, ResolvedTotals> byStatus) {
        double weightedSeconds = 0;
        long decided = 0;
        for (CaseStatus status : List.of(CaseStatus.APPROVED, CaseStatus.REJECTED)) {
            ResolvedTotals totals = byStatus.get(status.name());
            if (totals != null && totals.averageSeconds() != null) {
                weightedSeconds += totals.averageSeconds() * totals.count();
                decided += totals.count();
            }
        }
        return decided == 0 ? null : weightedSeconds / decided / 3600;
    }

    /** The database only returns non-empty buckets; without the quiet ones the chart interpolates over them. */
    private static List<TimelinePoint> fillGaps(
            List<TimelinePoint> points, Period period, TimelineGranularity granularity) {
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
