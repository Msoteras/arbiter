package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Which stage of the authorization chain a settlement is at — the Anexo II attributions of the
 * insurer's procedure: an analyst approves settlements "hasta el límite del atributo asignado por
 * rama", and above it someone else has to sign.
 *
 * <p><b>This is the settlement's state, not the case's.</b> A case whose settlement is waiting on
 * the referente stays in {@code PENDING_ANALYST_REVIEW}: the wait is an internal control, and the
 * insured has no reason to watch an administrative step go by. That's also why adding a sixth
 * {@link CaseStatus} was rejected — it would have leaked through the portal timeline and the
 * status labels for no gain.
 *
 * <p>Literals can't exceed 25 characters: {@code case_settlement.status} is {@code VARCHAR(25)} in
 * {@code db/init-multitenant.sql}.
 */
public enum SettlementStatus {

    /**
     * Signed off and effective. Either the amount fell within the analyst's own attribution — their
     * signature is the authorization, and there is no second signer to record — or the referente
     * cleared it afterwards.
     */
    AUTHORIZED,

    /**
     * Over the branch's limit, waiting for the referente. The analyst's decision is <b>not</b>
     * recorded yet: it takes effect when the authorization lands, so the case keeps no verdict
     * that never applied, and a return doesn't leave two decisions on one claim.
     */
    PENDING_AUTHORIZATION,

    /**
     * The referente sent it back with a reason. It's not a rejection of the claim — the analyst
     * still owns the case and can settle it again, at another amount or the same one with a better
     * justification.
     */
    RETURNED
}
