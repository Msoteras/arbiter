package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The claimed policy can't give rise to a case: the event fell outside its coverage window, fell
 * within the waiting period, or the declared dates don't hold up. 422 — the request is well
 * formed, what doesn't resolve is the contract it's filed against.
 *
 * <p><b>Why this cuts here and not at classification.</b> Without an in-force contract there's no
 * claim to analyze, so the case simply doesn't get created. That does <b>not</b> contradict
 * human-in-the-loop (CLAUDE.md #5): there's no automatic resolution of a case, there's a denuncia
 * that never becomes one — same as when the policy doesn't exist
 * ({@link UnresolvedCaseReferenceException}) or belongs to someone else
 * ({@link PolicyInsuredMismatchException}). Every case that does get created still needs an
 * analyst's decision.
 *
 * <p>The message is read by the insured in the wizard, so it's in Spanish.
 */
public class PolicyNotEligibleException extends RuntimeException {

    public PolicyNotEligibleException(String reason) {
        super(reason);
    }
}
