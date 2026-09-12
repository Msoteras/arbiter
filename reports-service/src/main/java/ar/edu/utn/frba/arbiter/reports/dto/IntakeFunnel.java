package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * What happened to the claims that came in during the period — a cohort, followed forward.
 *
 * <p><b>This asks a different question than {@link MetricsSummary}, and mixing them up is the
 * easiest way to misread the dashboard.</b> The summary's {@code resolvedCases} counts claims that
 * CLOSED in the period, whoever filed them and whenever. The funnel follows the claims that were
 * FILED in the period and asks how far each one got, even if it got there after the period ended.
 * A claim filed in March and decided in April is resolved-in-April for the summary and
 * decided-in-the-March-funnel for this one. Both are right; they answer different questions.
 *
 * @param reported  claims filed in the period — the mouth of the funnel, and the denominator of
 *                  every other step
 * @param analyzed  how many of them the model actually looked at. Never equals {@link #reported()}
 *                  when there are Fast Tracks in it: a Fast Track is decided by the rules engine
 *                  and the model never runs, which the database enforces — {@code llm_analysis}
 *                  rejects FAST_TRACK as a recommendation (decision #6).
 * @param decided   how many already carry an analyst's decision
 * @param fastTrack how many the rules engine let through on Fast Track. Not a shortcut past the
 *                  analyst: Fast Track speeds the file up, it does not close it (decision #5).
 * @param stillOpen how many have not reached a final status YET — as of now, not as of the period's
 *                  last day: for a period already past, what matters is what is still on someone's
 *                  desk today.
 */
public record IntakeFunnel(
        long reported,
        long analyzed,
        long decided,
        long fastTrack,
        long stillOpen
) {

    public static final IntakeFunnel EMPTY = new IntakeFunnel(0, 0, 0, 0, 0);
}
