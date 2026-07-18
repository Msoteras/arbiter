package ar.edu.utn.frba.arbiter.cases.exceptions;

public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(Long caseId) {
        super("Case " + caseId + " not found");
    }
}
