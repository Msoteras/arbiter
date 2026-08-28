package ar.edu.utn.frba.arbiter.cases.exceptions;

public class ExpertAssessmentNotFoundException extends RuntimeException {

    public ExpertAssessmentNotFoundException(Long caseId) {
        super("Case " + caseId + " has no expert assessment");
    }
}
