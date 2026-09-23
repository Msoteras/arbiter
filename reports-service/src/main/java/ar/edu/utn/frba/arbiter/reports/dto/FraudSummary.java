package ar.edu.utn.frba.arbiter.reports.dto;

import java.util.List;

/**
 * The aggregate head of the fraud report, counted over the very rows it lists.
 *
 * <p>Not the dashboard's fraud tile: {@link FraudDetection} counts cases <b>decided</b> in the period,
 * this counts cases <b>filed</b> in it. That is also why there is no amount here: stating the money
 * saved over a different population would make two screens disagree.
 *
 * @param totalClaims  every claim filed in the period and branch, flagged or not; the denominator of
 *                     both rates
 * @param flaggedRate  fraction between 0 and 1; null when there was nothing to divide
 * @param multiSignal  cases with two or more signals
 * @param fraudRate    lags on purpose: recent claims are still open, so the current month reads low
 * @param byAlertLevel labels are {@code CRITICAL}, {@code HIGH}, {@link #NOT_FLAGGED} and
 *                     {@link #NOT_SCORED}, not the four risk bands
 * @param bySignal     buckets overlap, so they add up to more than {@link #flagged}
 */
public record FraudSummary(
        long totalClaims,
        long flagged,
        Double flaggedRate,
        long multiSignal,
        long fraudDetermined,
        Double fraudRate,
        long backedByExpert,
        List<MetricCount> byAlertLevel,
        List<MetricCount> bySignal
) {

    /**
     * Scored LOW or MEDIUM, collapsed into one bucket: a low score is not an indicator of fraud, and
     * splitting it would read as an alert level.
     */
    public static final String NOT_FLAGGED = "NOT_FLAGGED";

    /** Scoring never ran. Kept apart from {@link #NOT_FLAGGED}: "no alert" and "no score" differ. */
    public static final String NOT_SCORED = "NOT_SCORED";

    public static final FraudSummary EMPTY =
            new FraudSummary(0, 0, null, 0, 0, null, 0, List.of(), List.of());
}
