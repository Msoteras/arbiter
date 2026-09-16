package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Why a case shows up in the fraud report. A case is listed when at least one of these fires, and
 * the ones that coincide are the cross the referent reads: a high score on its own is the engine's
 * opinion, a high score on a repeat claimant whose photo was already used in another claim is a
 * case someone has to look at today.
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
     * The insured filed more than one claim in the trailing 12 months counted from this one. "More
     * than one" is the plain meaning of "múltiples denuncias", not a business threshold: the
     * insurer's actual cap is the {@code MAX_EVENTS_YEAR} rule, which is a different question
     * (exceeding the cap is a hard stop; filing twice is something to look at).
     */
    REPEAT_CLAIMANT,

    /**
     * The forensic pass flagged at least one of the case's images — the same image already used in
     * another claim of the insurer, or found on the web. Read from {@code image_analysis}, which is
     * where classification-service records it.
     */
    FORENSIC_INCONSISTENCY
}
