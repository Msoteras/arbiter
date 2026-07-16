package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Fraud/risk band of a claim's support score, shared contract for the analyst's fraud-gauge.
 * Ordered from least to most risk. Literals are in English (see CLAUDE.md); the gauge labels
 * shown to the analyst (Bajo / Medio / Alto / Crítico) are the frontend's responsibility.
 *
 * The score is a parallel support signal for the analyst — never an automatic decision, and it
 * never feeds the LLM prompt. The concrete score-to-band thresholds are not fixed here: they live
 * in each insurer's scoring configuration (see BusinessRules.ScoringConfig), so the bands can be
 * tuned per insurer without code changes.
 */
public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
