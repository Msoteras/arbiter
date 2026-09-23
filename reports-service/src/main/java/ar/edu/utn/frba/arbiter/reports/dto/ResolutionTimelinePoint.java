package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * One point of the resolution timeline. The average is over the decided cases only, same cut as
 * {@link ResolutionSummary#averageMinutes()}; the counts travel with it because an average over two
 * cases and one over forty draw the same height.
 *
 * @param bucket         first day the point covers (Monday, or the 1st), in the insurer's time zone
 * @param resolved       lapsed cases included
 * @param averageMinutes null when none were decided in the bucket
 */
public record ResolutionTimelinePoint(LocalDate bucket, long resolved, long decided, Double averageMinutes) {}
