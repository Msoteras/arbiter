package ar.edu.utn.frba.arbiter.cases.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(CaseStatus from, CaseStatus to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
