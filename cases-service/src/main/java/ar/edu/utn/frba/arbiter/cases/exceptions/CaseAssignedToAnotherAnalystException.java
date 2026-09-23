package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * A plain 403, not the 404-instead-of-403 used for insureds: analysts already see every case in the
 * tenant, so there is no existence to hide.
 */
public class CaseAssignedToAnotherAnalystException extends RuntimeException {

    public CaseAssignedToAnotherAnalystException(Long caseId) {
        super("El expediente " + caseId + " está asignado a otro analista");
    }
}
