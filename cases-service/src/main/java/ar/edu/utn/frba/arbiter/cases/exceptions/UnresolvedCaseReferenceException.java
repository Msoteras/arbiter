package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * A denuncia named something the tenant's data doesn't have: a policy, a branch, a claim cause or
 * an insured. 422 rather than 404 — the request is well formed, its content just doesn't resolve.
 *
 * <p>The wizard only offers policies already synced from the insurer's DB, so in practice this
 * means either a client bypassing the wizard or a sync that hasn't run yet. Failing loudly is
 * deliberate: the old free-text columns swallowed these silently and the case looked fine until
 * someone read it.
 */
public class UnresolvedCaseReferenceException extends RuntimeException {

    public UnresolvedCaseReferenceException(String reference, String value) {
        super("No " + reference + " matching '" + value + "' in this insurer's data");
    }
}
