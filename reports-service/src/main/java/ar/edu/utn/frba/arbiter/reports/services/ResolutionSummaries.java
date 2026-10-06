package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTimelinePoint;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyResolution;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Folds the report's rows into its {@link ResolutionSummary} and timeline in memory, so the head, the
 * chart and the table always describe the same set. The rows are already loaded and the period is capped.
 */
public final class ResolutionSummaries {

    private ResolutionSummaries() {
    }

    public static ResolutionSummary of(List<ResolutionReportRow> rows) {
        if (rows.isEmpty()) {
            return ResolutionSummary.EMPTY;
        }
        long total = rows.size();
        long fastTrack = rows.stream()
                .filter(row -> row.classification() == Classification.FAST_TRACK)
                .count();
        // Times are averaged over decided cases only, like the dashboard: a lapsed case measures the
        // insured's silence, not the operation.
        List<ResolutionReportRow> decided = rows.stream().filter(ResolutionSummaries::decided).toList();

        return new ResolutionSummary(
                total,
                decided.size(),
                average(decided, ResolutionReportRow::totalMinutes),
                average(decided, ResolutionReportRow::waitingMinutes),
                fastTrack,
                (double) fastTrack / total,
                countBy(rows, row -> row.finalStatus().name()),
                countBy(rows, ResolutionReportRow::claimCause));
    }

    /**
     * Averages can differ from {@link #of} by under a minute: the days add up seconds, the rows truncate
     * each case to whole minutes first.
     */
    public static ResolutionSummary ofStored(List<DailyResolution> days, List<MetricCount> byClaimCause) {
        long total = days.stream().mapToLong(DailyResolution::resolved).sum();
        if (total == 0) {
            return ResolutionSummary.EMPTY;
        }
        List<DailyResolution> decided = days.stream()
                .filter(day -> CaseStatus.APPROVED.name().equals(day.status())
                        || CaseStatus.REJECTED.name().equals(day.status()))
                .toList();
        long decidedCases = decided.stream().mapToLong(DailyResolution::resolved).sum();
        long fastTrack = days.stream().mapToLong(DailyResolution::fastTrack).sum();
        Map<String, Long> byStatus = days.stream().collect(Collectors.groupingBy(
                DailyResolution::status, Collectors.summingLong(DailyResolution::resolved)));

        return new ResolutionSummary(
                total,
                decidedCases,
                averageMinutes(decided.stream().mapToDouble(DailyResolution::totalSeconds).sum(), decidedCases),
                averageMinutes(decided.stream().mapToDouble(DailyResolution::waitingSeconds).sum(), decidedCases),
                fastTrack,
                (double) fastTrack / total,
                byStatus.entrySet().stream()
                        .map(entry -> new MetricCount(entry.getKey(), entry.getValue()))
                        .sorted(Comparator.comparingLong(MetricCount::count).reversed()
                                .thenComparing(MetricCount::label))
                        .toList(),
                byClaimCause);
    }

    private static Double averageMinutes(double seconds, long cases) {
        return cases == 0 ? null : seconds / cases / 60;
    }

    /**
     * Emits every bucket of the period, empty ones included: dropping them would draw a continuous line
     * over a stretch where nothing closed. Averages cover decided cases only; see
     * {@link ResolutionTimelinePoint}.
     */
    public static List<ResolutionTimelinePoint> timeline(List<ResolutionReportRow> rows, LocalDate from,
                                                         LocalDate to, ZoneId zone,
                                                         TimelineGranularity granularity) {
        Map<LocalDate, List<ResolutionReportRow>> byBucket = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> granularity.bucketOf(LocalDate.ofInstant(row.resolvedAt(), zone))));

        List<ResolutionTimelinePoint> points = new ArrayList<>();
        for (LocalDate bucket = granularity.bucketOf(from); !bucket.isAfter(to);
                bucket = granularity.next(bucket)) {
            List<ResolutionReportRow> inBucket = byBucket.getOrDefault(bucket, List.of());
            List<ResolutionReportRow> decided = inBucket.stream()
                    .filter(ResolutionSummaries::decided)
                    .toList();
            points.add(new ResolutionTimelinePoint(bucket, inBucket.size(), decided.size(),
                    average(decided, ResolutionReportRow::totalMinutes)));
        }
        return points;
    }

    /** Same cut as the dashboard's {@code resolutionSplit}: closed APPROVED or REJECTED. */
    private static boolean decided(ResolutionReportRow row) {
        return row.finalStatus() == CaseStatus.APPROVED || row.finalStatus() == CaseStatus.REJECTED;
    }

    private static Double average(List<ResolutionReportRow> rows,
                                  ToLongFunction<ResolutionReportRow> minutes) {
        return rows.isEmpty() ? null : rows.stream().mapToLong(minutes).average().orElseThrow();
    }

    /**
     * Busiest first, ties by label so the same data always renders the same way. Buckets carry enum
     * literals; the frontend and {@code ReportLabels} translate them.
     */
    private static List<MetricCount> countBy(List<ResolutionReportRow> rows,
                                             Function<ResolutionReportRow, String> bucket) {
        Map<String, Long> counts = rows.stream()
                .collect(Collectors.groupingBy(bucket, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> new MetricCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(MetricCount::count).reversed()
                        .thenComparing(MetricCount::label))
                .toList();
    }
}
