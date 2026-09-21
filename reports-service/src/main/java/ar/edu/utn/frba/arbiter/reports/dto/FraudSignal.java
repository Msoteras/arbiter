package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Why a case shows up in the fraud report. A case is listed when at least one of these fires, and
 * the ones that coincide are the cross the referent reads: a high score on its own is the engine's
 * opinion, a high score on a repeat claimant whose photo was already used in another claim is a
 * case someone has to look at today.
 *
 * <p><b>How often the insured claims is NOT one of these.</b> Filing twice in a year is not an
 * indication of fraud — a phone gets stolen twice — and that fact is already inside the score:
 * {@code claim_frequency} is one of its weighted factors, saturating at three prior claims.
 * Counting it again here made one fact fire two signals, and inflated the figure the report leads
 * with: the cases where two DIFFERENT things coincide. The count travels with every row as context
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
    FORENSIC_INCONSISTENCY,

    /**
     * The {@code document_inconsistency} risk factor found a contradiction between an attached
     * document and the claim — a document dated before the event, an amount or an IMEI that
     * doesn't match, or a police certificate dated differently from what the insured declared.
     * Read from the latest {@code risk_analysis.risk_breakdown} for the case; only counts when the
     * insurer has the factor active in its scoring config, same as {@code HIGH_RISK_SCORE} only
     * counts when the engine actually scored the case.
     */
    DOCUMENT_INCONSISTENCY
}
