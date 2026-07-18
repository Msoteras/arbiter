package ar.edu.utn.frba.arbiter.cases.exceptions;

public class InvalidAnalystDecisionException extends RuntimeException {

    public InvalidAnalystDecisionException(String decision) {
        super("Invalid analyst decision '" + decision + "'. Expected APPROVE/APROBAR or REJECT/RECHAZAR.");
    }
}
