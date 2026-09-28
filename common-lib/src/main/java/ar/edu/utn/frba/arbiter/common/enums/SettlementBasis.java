package ar.edu.utn.frba.arbiter.common.enums;

/**
 * How the ceiling of an indemnity is worked out for a coverage, before any deduction. Both values
 * come from the insurer's product documents. Literals can't exceed 30 characters
 * ({@code VARCHAR(30)} columns).
 */
public enum SettlementBasis {

    /** The sum insured, flat. A recorded replacement value is evidence for the file, not a cap. */
    SUM_INSURED,

    /**
     * The lesser of the sum insured and what replacing the item costs today (annex 340, art. 7).
     * The replacement value is the settlement's base: without it there is nothing to pay yet, and
     * the case can't be approved.
     */
    LESSER_OF_SUM_AND_REPLACEMENT
}
