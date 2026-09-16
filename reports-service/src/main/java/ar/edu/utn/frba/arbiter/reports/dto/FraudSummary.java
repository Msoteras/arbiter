package ar.edu.utn.frba.arbiter.reports.dto;

import java.util.List;

/**
 * The aggregate head of the fraud report, counted over the very rows it lists.
 *
 * <p><b>Not the dashboard's fraud tile.</b> {@link FraudDetection} measures the cases <b>decided</b>
 * in the period and answers "how much did we not pay because we caught it"; this one measures the
 * cases <b>filed</b> in the period that carry a signal, and answers "what do we have to look at".
 * A case flagged in September and rejected in October is counted here in September and there in
 * October, on purpose.
 *
 * <p>That is also why there is no amount here. The money saved is the dashboard's figure, over its
 * own population; repeating it against a different one is how two screens end up stating two
 * numbers for the same sentence.
 *
 * @param totalClaims     every claim filed in the period and branch, flagged or not — the
 *                        denominator of the two rates below. Stated as a figure of its own because
 *                        "8 expedientes con indicios" means nothing until you know whether it is 8
 *                        out of 20 or 8 out of 2000
 * @param flagged         listed cases, matching the number of rows
 * @param flaggedRate     {@code flagged / totalClaims}, a fraction between 0 and 1; null when there
 *                        was nothing to divide. Measured at intake, so it does not lag
 * @param multiSignal     how many of them carry two or more signals — the cross, and the reason the
 *                        report exists: one signal is a hint, two coinciding is a shortlist
 * @param fraudDetermined of the flagged, how many an analyst determined as fraud
 * @param fraudRate       {@code fraudDetermined / totalClaims}. <b>It lags on purpose</b>: the
 *                        population is the claims filed in the period, and the ones filed most
 *                        recently are still open, so a rate over the current month reads low and
 *                        rises as those cases close
 * @param backedByExpert  of those, how many have an expert assessment behind the determination
 * @param byAlertLevel    the flagged cases by whether the score alerted, riskiest first. The labels
 *                        are {@code CRITICAL}, {@code HIGH}, {@link #NOT_FLAGGED} and
 *                        {@link #NOT_SCORED} — not the four risk bands
 * @param bySignal        how many cases each signal fired on. The buckets overlap — a case with two
 *                        signals is counted in both — so they add up to more than {@link #flagged}
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
     * The score ran and did not alert: bands LOW and MEDIUM collapsed into one bucket.
     *
     * <p>They are one bucket and not two because a fraud report has nothing to say about a low
     * score — it isn't an indicator of fraud, and breaking it out would invite reading "Bajo" as a
     * level of alert. These cases are listed for another reason entirely, which the signals say.
     */
    public static final String NOT_FLAGGED = "NOT_FLAGGED";

    /**
     * The scoring never ran on the case — a Fast Track, or a classification that failed. Kept apart
     * from {@link #NOT_FLAGGED} on purpose: "the score said nothing" and "there is no score" look
     * the same on screen and mean very different things, the second one operationally.
     */
    public static final String NOT_SCORED = "NOT_SCORED";

    /** No claims at all in the period: no rows, and no denominator to state a rate against. */
    public static final FraudSummary EMPTY =
            new FraudSummary(0, 0, null, 0, 0, null, 0, List.of(), List.of());
}
