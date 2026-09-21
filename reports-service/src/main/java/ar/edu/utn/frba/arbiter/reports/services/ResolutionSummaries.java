package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTimelinePoint;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;

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
 * Folds the report's rows into its {@link ResolutionSummary} and its timeline.
 *
 * <p>In memory and not in SQL: the rows are already loaded to render the file, a period is capped at
 * 366 days of one insurer's closed claims, and counting them here is what guarantees the head, the
 * chart and the table describe the same set. Doing it with a second query would reopen the door to
 * them disagreeing whenever only one of them changed.
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
        // The times are averaged over the decided ones only, the same population the dashboard
        // measures: a lapsed case wasn't resolved by anybody, it measured the insured's silence.
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
     * The same rows spread over the period, one point per bucket, so the average on the card can be
     * read as a trajectory instead of a single number.
     *
     * <p>Every bucket of the period is emitted, including the ones where nothing closed: a gap in
     * the line is information — that fortnight the company resolved nothing — and dropping the
     * empty buckets would draw a continuous line over it, moving the neighbouring points closer
     * together and inventing a smoothness the data doesn't have.
     *
     * <p>The average per bucket is over the DECIDED cases, exactly like
     * {@link ResolutionSummary#averageMinutes()}; the count next to it is every case that closed.
     * Two populations on purpose — see {@link ResolutionTimelinePoint}.
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

    /**
     * Decided means an analyst pronounced on it. Same cut as the dashboard's
     * {@code resolutionSplit}, which counts the cases that closed APPROVED or REJECTED.
     */
    private static boolean decided(ResolutionReportRow row) {
        return row.finalStatus() == CaseStatus.APPROVED || row.finalStatus() == CaseStatus.REJECTED;
    }

    /** Null and not zero with nothing to average: an unknown average, not a zero one. */
    private static Double average(List<ResolutionReportRow> rows,
                                  ToLongFunction<ResolutionReportRow> minutes) {
        return rows.isEmpty() ? null : rows.stream().mapToLong(minutes).average().orElseThrow();
    }

    /**
     * Busiest bucket first — the distribution is read to find where the volume is, so the answer
     * should be the first line. Ties break by label so the same data always renders the same way,
     * in the file as on the screen.
     *
     * <p>Status buckets carry the enum literal, not a Spanish label: translating is the frontend's
     * job for the preview and {@code ReportLabels}' job for the exports.
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
