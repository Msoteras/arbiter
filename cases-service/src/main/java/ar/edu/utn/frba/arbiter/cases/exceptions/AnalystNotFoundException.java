package ar.edu.utn.frba.arbiter.cases.exceptions;

public class AnalystNotFoundException extends RuntimeException {

    public AnalystNotFoundException(Long analystId) {
        super("Analyst " + analystId + " not found or not assignable");
    }
}
