package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * One point of the timeline chart: how the period moved, in two series.
 *
 * @param bucket   first calendar day the point covers (the Monday of the week, the 1st of the
 *                 month), in the insurer's time zone
 * @param reported claims filed in that bucket
 * @param resolved claims that reached a final status in that bucket — not the same claims, and
 *                 that's the point: the gap between the two lines is the backlog building up
 *                 or draining.
 */
public record TimelinePoint(LocalDate bucket, long reported, long resolved) {}
