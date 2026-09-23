package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * rules-service didn't answer. Kept distinct from "this insurer doesn't derive" so an outage is
 * never presented to the analyst as a business policy.
 */
public class RulesUnavailableException extends RuntimeException {

    public RulesUnavailableException(Throwable cause) {
        super("No se pudo consultar el motor de reglas", cause);
    }
}
