package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Deciding and deriving require owning the case, so the assign-then-act order is enforced instead
 * of letting an action silently stand in for an assignment nobody made.
 */
public class CaseNotAssignedException extends RuntimeException {

    public CaseNotAssignedException(Long caseId) {
        super("El expediente " + caseId + " no tiene analista asignado — hay que asignarlo antes de actuar sobre él");
    }
}
