package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Why a case shows up in the fraud report. A case is listed when at least one of these fires, and
 * the two coinciding is the cross the referent reads: a high score on its own is the engine's
 * opinion, a high score on a claim whose photo was already used in another one is a case somebody
 * has to look at today.
 *
 * <p><b>How often the insured claims is NOT one of these.</b> Filing twice in a year is not an
 * indication of fraud — a phone gets stolen twice — and, more to the point, that fact is already
 * inside the score: {@code claim_frequency} is one of its weighted factors. Counting it again here
 * made the same fact fire two signals and inflated the very figure the report leads with, the cases
 * where two DIFFERENT things coincide. The count travels with each row as context
 * ({@code FraudReportRow.claimsInWindow}), which is what it is.
 *
 * <p>None of them determines fraud. They say what the platform noticed, which is the only thing a
 * report can honestly say — the determination is the analyst's, and it travels in
 * {@code FraudReportRow.fraudDetermined}.
 *
 * <p>Lives here and not in common-lib because no other module needs it: it is how this report
 * explains itself, not a domain concept the platform shares (same call as {@code ReportFormat}).
 */
public enum FraudSignal {

    /**
     * The scoring engine put the case in its two top bands. The thresholds are the insurer's
     * ({@code score_band}), not this report's — it only reads the band the engine already wrote.
     */
    HIGH_RISK_SCORE,

    /**
     * The forensic pass flagged at least one of the case's images — the same image already used in
     * another claim of the insurer, or found on the web. Read from {@code image_analysis}, which is
     * where classification-service records it.
     */
    FORENSIC_INCONSISTENCY
}
