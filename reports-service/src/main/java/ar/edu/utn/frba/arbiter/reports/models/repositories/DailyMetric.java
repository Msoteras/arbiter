package ar.edu.utn.frba.arbiter.reports.models.repositories;

/**
 * The figures stored per day in {@code metrics_daily_value}, one row per metric. The catalog lives here
 * and not in the base: adding a metric is a literal here and a line in its source's INSERT in
 * {@link DailyMetricsRepository}, without DDL.
 *
 * <p>Sums and counts only, never a rate or an average: an average can't be combined with another day's,
 * its numerator and denominator can.
 */
public enum DailyMetric {

    // Intake: claims filed on the day.
    REPORTED,
    REPORTED_FAST_TRACK,

    // Resolution: claims that reached a final status on the day, segmented by that status.
    RESOLVED,
    TOTAL_SECONDS,
    /** Spent waiting on third parties, which pauses the art. 56 term. */
    WAITING_SECONDS,
    RESOLVED_FAST_TRACK,
    FAST_TRACK_SECONDS,
    /** Resolved within the case's art. 56 deadline. */
    ON_TIME,
    REOPENED,
    /** With an actionable model recommendation. */
    AGREEMENT_ELIGIBLE,
    /** Of those, the ones the analyst followed. */
    AGREEMENT_AGREED,
    FRAUD_DETERMINED,
    FRAUD_BACKED_BY_EXPERT,
    FRAUD_CLAIMED_AMOUNT,

    // Settlement: settlements authorized on the day.
    SETTLEMENTS,
    SETTLED_AMOUNT,
    CLAIMED_AMOUNT,
    /** Settlements whose claim carries a claimed amount (it is optional). */
    CLAIMED_CASES,
    DEDUCTIBLE_AMOUNT,
    INSTALLMENTS_AMOUNT,
    OVERDUE_AMOUNT;

    /**
     * Raise it when a metric is added or its definition changes. A day stored with a lower version is
     * stored again the next time a report asks for it, so it never reads 0 for a metric it predates.
     */
    public static final int DEFINITIONS_VERSION = 1;
}
