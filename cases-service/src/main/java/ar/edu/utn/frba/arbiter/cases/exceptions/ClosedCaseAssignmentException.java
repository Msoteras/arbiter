package ar.edu.utn.frba.arbiter.cases.exceptions;

/** A closed case keeps whoever resolved it; it has to be reopened before it changes hands. */
public class ClosedCaseAssignmentException extends RuntimeException {

    public ClosedCaseAssignmentException(Long caseId) {
        super("El expediente " + caseId + " está cerrado: reabrilo para cambiar el analista asignado");
    }
}
