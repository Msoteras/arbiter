package ar.edu.utn.frba.arbiter.cases.exceptions;

/** The id sent to the assign endpoint doesn't belong to any assignable analyst. */
public class AnalystNotFoundException extends RuntimeException {

    public AnalystNotFoundException(Long analystId) {
        super("Analyst " + analystId + " not found or not assignable");
    }
}
