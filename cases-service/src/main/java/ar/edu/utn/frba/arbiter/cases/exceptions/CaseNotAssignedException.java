package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Nobody owns this case yet, so nobody can act on it — deciding and deriving to an expert are
 * ownership's payoff, not separate permissions. Forces the assign → act order instead of letting
 * an action silently stand in for an assignment nobody made.
 */
public class CaseNotAssignedException extends RuntimeException {

    public CaseNotAssignedException(Long caseId) {
        super("El expediente " + caseId + " no tiene analista asignado — hay que asignarlo antes de actuar sobre él");
    }
}
