package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * One point of the resolution report's timeline: how long the cases that closed in that bucket took.
 *
 * <p>Two populations, on purpose, and the chart draws both: {@code resolved} is every case that
 * closed in the bucket, {@code averageMinutes} is the average over the {@code decided} ones only —
 * the same cut {@link ResolutionSummary#averageMinutes()} uses, so a point of the line can never
 * disagree with the figure on the card above it. Keeping the count next to the average is what
 * stops the line from lying by omission: an average over two cases and one over forty are drawn the
 * same height, and only the volume says which one means something.
 *
 * @param bucket         first calendar day the point covers (the Monday of the week, the 1st of the
 *                       month), in the insurer's time zone
 * @param resolved       cases that reached a final status in the bucket, lapsed ones included
 * @param decided        how many of those an analyst actually decided
 * @param averageMinutes average resolution time over the decided ones; null when none were decided
 *                       in the bucket — unknown, not zero
 */
public record ResolutionTimelinePoint(LocalDate bucket, long resolved, long decided, Double averageMinutes) {}
