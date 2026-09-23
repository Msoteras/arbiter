package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Which settlement field a suggested amount fills. Depends on the coverage, not on the source: an
 * expert's figure can land on either.
 */
public enum SettlementSuggestionTarget {
    /** Feeds the calculation (repair quote or replacement value); taking it re-runs the formula. */
    ACCREDITED_AMOUNT,

    /** The amount to pay; taking it is an adjustment over the formula, so it needs a justification. */
    SETTLED_AMOUNT
}
