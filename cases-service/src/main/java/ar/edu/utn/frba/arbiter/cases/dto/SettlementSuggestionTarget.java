package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Which field of the settlement sheet a suggested amount answers. The same number means different
 * things depending on the coverage, and putting it in the wrong box would quietly change what the
 * analyst is agreeing to.
 *
 * <p>It is not a property of where the number came from: an expert's determination lands on either
 * one, depending on whether the coverage has an accredited amount to take at all.
 */
public enum SettlementSuggestionTarget {
    /**
     * The accredited amount that feeds the calculation — the repair quote a repair is settled on,
     * or the replacement value that caps a total loss settled by the lesser of the two. Taking it
     * re-runs the formula.
     */
    ACCREDITED_AMOUNT,

    /**
     * The amount to pay itself. This is where an expert's determination goes when the coverage
     * settles by sum insured: there is no accredited amount in that formula, but what the expert
     * decided should be paid is still the analyst's business. Taking it is an adjustment over what
     * the formula calculated, so it carries the adjustment justification like any other.
     */
    SETTLED_AMOUNT
}
