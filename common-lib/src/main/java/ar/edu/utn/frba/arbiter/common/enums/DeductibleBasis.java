package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What a coverage's deductible percentage is applied to. Each insurer's policy says it differently
 * (BBVA's phone policy: "10% de la suma asegurada"; others: "% del siniestro"), so it is set per
 * coverage by the referent. Literals can't exceed 20 characters ({@code VARCHAR(20)} columns).
 */
public enum DeductibleBasis {

    /** A fixed share of the sum insured, however small the loss: a cheap repair may pay nothing. */
    SUM_INSURED,

    /** A share of what is actually indemnified: the replacement value or the quote, once capped. */
    LOSS_AMOUNT
}
