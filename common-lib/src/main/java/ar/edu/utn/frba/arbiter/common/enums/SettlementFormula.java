package ar.edu.utn.frba.arbiter.common.enums;

/**
 * How a claim is settled, which follows from what happened to the item — not from how much it was
 * worth. Configured per coverage by the referente, and frozen onto each settlement.
 *
 * <p>It lives on the coverage and not on the hecho generador because the insurer's own catalog
 * already splits them that way: "Robo de celular" and "Hurto" are losses, "Daño accidental" is
 * damage. And {@code PolicyCoverageResolver} already works out which coverage answers for a claim
 * cause, so putting it here means the answer is decided once, where the rest of the coverage's
 * terms already are.
 *
 * <p>Literals can't exceed 20 characters: {@code coverage.settlement_formula} and
 * {@code case_settlement.formula} are {@code VARCHAR(20)} in {@code db/init-multitenant.sql}.
 */
public enum SettlementFormula {

    /**
     * The item is gone. The Celulares manual: sum insured, less the franchise, less the premium
     * instalments still to fall due — those come off because the loss extinguishes the policy and
     * the rest of the year's premium is collected out of the indemnity.
     */
    TOTAL_LOSS,

    /**
     * The item survived, damaged — a screen broken while someone tried to snatch the phone. Pays
     * the accredited repair quote, capped by the sum insured, less the franchise.
     *
     * <p><b>It does not deduct the instalments still to fall due.</b> That deduction only makes
     * sense when the policy is extinguished by the loss; after a repair the contract stays alive
     * and the insured keeps paying it month by month. Deducting them here would charge the rest of
     * the year to someone who still has the cover they're paying for.
     *
     * <p>The quote is the <b>basis</b>, not extra evidence: without one there is no amount to pay,
     * and the proposal comes out at zero rather than falling back to the sum insured.
     */
    REPAIR
}
