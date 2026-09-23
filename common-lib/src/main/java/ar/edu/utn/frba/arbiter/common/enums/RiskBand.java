package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Fraud/risk band of a claim's support score, ordered from least to most risk. The score is a
 * support signal for the analyst only: it never decides and never feeds the LLM prompt. The
 * score-to-band thresholds live in each insurer's scoring configuration, not here.
 */
public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
