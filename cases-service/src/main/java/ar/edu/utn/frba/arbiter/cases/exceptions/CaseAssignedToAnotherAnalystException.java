package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Only the analyst a case is assigned to can approve or reject it — deciding is the owner's call,
 * not any analyst's. A plain 403 and not the 404-instead-of-403 pattern {@code CaseAccessPolicy}
 * uses for an insured: analysts already see every case in the tenant (it's their job), so there's
 * no existence to hide here, just an action that isn't theirs to take.
 */
public class CaseAssignedToAnotherAnalystException extends RuntimeException {

    public CaseAssignedToAnotherAnalystException(Long caseId) {
        super("El expediente " + caseId + " está asignado a otro analista");
    }
}
