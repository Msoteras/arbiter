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

/**
 * The referent's dashboard: how their own portfolio of claims is running over a period. Always
 * scoped to the caller's insurer — the tenant schema their JWT resolves to — so there is no
 * parameter that could point it at another company.
 */
@Service
@RequiredArgsConstructor
public class ClaimMetricsService {

    /**
     * A year, leap day included. Same ceiling as the resolution report: not a business rule, a
     * guard on how much one request can scan.
     */
    static final int MAX_PERIOD_DAYS = 366;

    /** What the dashboard opens on when the caller names no period. */
    private static final MetricsRange DEFAULT_RANGE = MetricsRange.MONTH;

    private final ClaimMetricsRepository claimMetricsRepository;
    private final RulesServiceClient rulesServiceClient;
    private final Clock clock;

    /**
     * @param range shortcut period; mutually exclusive with {@code from}/{@code to}. With all three
     *              absent the dashboard gets {@link #DEFAULT_RANGE}.
     */
    public ClaimMetrics generate(MetricsRange range, LocalDate from, LocalDate to, MetricsFilter filter) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        Period period = resolvePeriod(range, from, to);
        ZoneId zone = clock.getZone();

        // Whole calendar days in the insurer's time zone, both ends included: "hasta el 31/08"
        // means up to the last second of that day, so the upper bound is the next midnight,
        // exclusive. Same convention as the resolution report.
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
                resolutionTarget(start, end, filter),
                claimMetricsRepository.countByStatus(start, end, filter),
                claimMetricsRepository.countByBranch(start, end, filter),
                claimMetricsRepository.countByClassification(start, end, filter),
                claimMetricsRepository.countByRiskBand(start, end, filter),
                fillGaps(claimMetricsRepository.timeline(start, end, granularity, zone, filter),
                        period, granularity));
    }

    /**
     * El objetivo que fijó la aseguradora y cuántas decisiones se pasaron de él.
     *
     * <p>La consulta sólo corre si hay objetivo: sin uno no hay contra qué contar, y preguntarle a
     * la base "cuántos superaron nada" es una consulta de más en cada carga del tablero.
     */
    private ResolutionTarget resolutionTarget(Instant start, Instant end, MetricsFilter filter) {
        ResolutionTarget target = rulesServiceClient.resolutionTarget();
        if (!target.enabled() || target.targetDays() == null) {
            return ResolutionTarget.UNSET;
        }
        return target.withExceeded(
                claimMetricsRepository.countDecidedOverTarget(start, end, target.targetDays(), filter));
    }

    /**
     * The same summary over the stretch of equal length ending the day before this period starts.
     * It's what turns "30,8 días" into "30,8 días, 4 más que antes" — a figure nobody can read
     * without something to read it against.
     *
     * <p>Equal length and immediately before, rather than "the previous calendar month": a custom
     * 17-day period has to compare against 17 days or the delta is measuring the calendar.
     */
    private MetricsSummary previousSummary(Period period, ZoneId zone, MetricsFilter filter) {
        long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        LocalDate previousTo = period.from().minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        Instant start = previousFrom.atStartOfDay(zone).toInstant();
        Instant end = previousTo.plusDays(1).atStartOfDay(zone).toInstant();
        // Sin la partición: los deltas del encabezado se leen sobre el total, y traerla costaría
        // una consulta con ventanas más por cada carga del tablero para un número que no se muestra.
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
        // Decided, not resolved: a lapsed claim closed without anyone deciding it, so it belongs in
        // neither the rates nor the average (see MetricsSummary).
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

    /**
     * The database averaged each final status separately, so the overall figure has to weigh them
     * by how many claims each one closed — averaging the two averages would let a single rejection
     * count as much as fifty approvals.
     */
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

    /**
     * The database only returns buckets where something happened. A line chart needs the quiet ones
     * too: without them a fortnight with no claims reads as a straight line between its ends
     * instead of as a flat zero.
     */
    private static List<TimelinePoint> fillGaps(
            List<TimelinePoint> points, Period period, TimelineGranularity granularity) {
        Map<LocalDate, TimelinePoint> found = points.stream()
                .collect(Collectors.toMap(TimelinePoint::bucket, Function.identity()));
        List<TimelinePoint> complete = new ArrayList<>();
        // Starts at the bucket the period's first day belongs to, which can predate it: a period
        // starting on a Wednesday is reported under the Monday of that week.
        LocalDate bucket = granularity.bucketOf(period.from());
        while (!bucket.isAfter(period.to())) {
            complete.add(found.getOrDefault(bucket, new TimelinePoint(bucket, 0, 0)));
            bucket = granularity.next(bucket);
        }
        return complete;
    }
}
