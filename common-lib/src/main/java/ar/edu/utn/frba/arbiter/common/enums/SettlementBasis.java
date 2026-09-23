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
     * The lesser of the sum insured and the accredited replacement value (clause 340, art. 7).
     * Without a replacement value on file it falls back to the sum insured.
     */
    LESSER_OF_SUM_AND_REPLACEMENT
}
