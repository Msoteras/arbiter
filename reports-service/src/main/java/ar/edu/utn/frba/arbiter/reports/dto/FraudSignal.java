package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Why a case shows up in the fraud report. None of these determines fraud; that is the analyst's call.
 *
 * <p>Claim frequency is deliberately not a signal: it is already a weighted factor of the score
 * ({@code claim_frequency}), and counting it again would make one fact fire two signals. It travels as
 * context in {@code FraudReportRow.claimsInWindow}.
 */
public enum FraudSignal {

    /** The engine put the case in its two top bands, per the insurer's {@code score_band} thresholds. */
    HIGH_RISK_SCORE,

    /** At least one image was reused from another claim or found on the web ({@code image_analysis}). */
    FORENSIC_INCONSISTENCY,

    /**
     * The {@code document_inconsistency} factor found an attachment contradicting the claim. Read from
     * the latest {@code risk_analysis.risk_breakdown}; only counts when the insurer has the factor active.
     */
    DOCUMENT_INCONSISTENCY
}
