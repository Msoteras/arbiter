package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * rules-service didn't answer. Distinct from "this insurer doesn't derive": collapsing the two
 * would present an outage as a business policy, and the analyst would read "no corresponde
 * derivar" when the truth is that nobody could tell.
 */
public class RulesUnavailableException extends RuntimeException {

    public RulesUnavailableException(Throwable cause) {
        super("No se pudo consultar el motor de reglas", cause);
    }
}
