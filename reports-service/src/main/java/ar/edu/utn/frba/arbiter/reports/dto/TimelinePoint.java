package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * One point of the dashboard timeline.
 *
 * @param bucket   first day the point covers (Monday, or the 1st), in the insurer's time zone
 * @param resolved claims closed in the bucket, not the same claims as {@code reported}: the gap is the
 *                 backlog growing or draining
 */
public record TimelinePoint(LocalDate bucket, long reported, long resolved) {}
