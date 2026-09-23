package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Kept apart from {@code ExpertVerdict} on purpose: that one is fraud vocabulary, and a
 * {@code FRAUD_CONFIRMED} leaves a fraud record on the insured that a repair must never trigger.
 */
public enum RepairOutcome {
    REPAIRED,
    IRREPARABLE,
    QUOTE_SENT
}
