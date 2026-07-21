package ar.edu.utn.frba.arbiter.cases.exceptions;

public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(String policyNumber) {
        super("No encontramos una póliza vigente con el número " + policyNumber);
    }
}
