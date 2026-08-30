package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Nobody owns this case yet, so nobody can decide on it — deciding is ownership's payoff, not a
 * separate permission. Forces the assign → decide order instead of letting a decision silently
 * stand in for an assignment nobody made.
 */
public class CaseNotAssignedException extends RuntimeException {

    public CaseNotAssignedException(Long caseId) {
        super("El expediente " + caseId + " no tiene analista asignado — hay que asignarlo antes de decidir");
    }
}
