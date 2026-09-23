package ar.edu.utn.frba.arbiter.common.enums;

/**
 * How a claim is settled, which follows from what happened to the item, not from its value.
 * Configured per coverage (not per claim cause) because the insurer's catalog already splits
 * coverages that way. Literals can't exceed 20 characters ({@code VARCHAR(20)} columns).
 */
public enum SettlementFormula {

    /**
     * The item is gone: sum insured, less the franchise, less the pending premium instalments —
     * the loss extinguishes the policy, so the rest of the year's premium comes out of the indemnity.
     */
    TOTAL_LOSS,

    /**
     * The item survived, damaged: the accredited repair quote, capped by the sum insured, less the
     * franchise. <b>Doesn't deduct pending instalments</b>: the policy stays alive and the insured
     * keeps paying it. Without a quote the proposal is zero, not the sum insured.
     */
    REPAIR
}
