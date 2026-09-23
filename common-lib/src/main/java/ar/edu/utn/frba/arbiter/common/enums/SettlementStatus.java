package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Stage of a settlement in the authorization chain: an analyst approves up to their attribution
 * per branch, and above it the referente has to sign.
 *
 * <p><b>This is the settlement's state, not the case's.</b> A case waiting on the referente stays
 * in {@code PENDING_ANALYST_REVIEW}: it's an internal control the insured shouldn't see in the
 * portal timeline. Literals can't exceed 25 characters ({@code VARCHAR(25)}).
 */
public enum SettlementStatus {

    /** Within the analyst's own attribution, or cleared by the referente afterwards. */
    AUTHORIZED,

    /**
     * Over the branch's limit, waiting for the referente. The analyst's decision isn't recorded
     * until then, so a return doesn't leave two decisions on one claim.
     */
    PENDING_AUTHORIZATION,

    /** Sent back by the referente with a reason. Not a rejection: the analyst can settle it again. */
    RETURNED
}
