package ar.edu.utn.frba.arbiter.cases.dto;

import java.time.Instant;

/**
 * The counts behind the home screen, in one call. For an analyst every count is over their own
 * open cases, except {@code unassigned}, which by definition belongs to nobody; for the referente
 * everything covers the whole insurer.
 *
 * @param stale                open cases with no change in {@code staleDays} days
 * @param oldestUnassignedAt   when the longest-waiting unassigned case was reported; null if there is none
 */
public record HomeSummaryResponse(
        Deadlines deadlines,
        long expertReportsReceived,
        long repairReportsReceived,
        long returnedByReferent,
        long awaitingReferent,
        long stale,
        int staleDays,
        long classificationFailed,
        long unassigned,
        Instant oldestUnassignedAt
) {

    /**
     * Open cases by where they stand against the art. 56 term. The four add up to every open case.
     *
     * @param dueSoon within {@code DeadlinePriority.WATCH_DAYS}, today included
     * @param paused  in a status that freezes the term, so its stored deadline means nothing yet
     */
    public record Deadlines(long onTime, long dueSoon, long overdue, long paused) {
    }
}
