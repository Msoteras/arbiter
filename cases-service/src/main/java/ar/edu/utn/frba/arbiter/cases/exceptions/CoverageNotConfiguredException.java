package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The insurer database lists the policy's coverages, but the referent hasn't configured any of them
 * in Arbiter yet, so there is nothing to evaluate a claim against. Not a data error: the insured
 * reads it and the fix is on the insurer's side, hence a message in Spanish.
 */
public class CoverageNotConfiguredException extends RuntimeException {

    public CoverageNotConfiguredException() {
        super("Tu aseguradora todavía no habilitó denuncias online para esta póliza. Comunicate con ella.");
    }
}
