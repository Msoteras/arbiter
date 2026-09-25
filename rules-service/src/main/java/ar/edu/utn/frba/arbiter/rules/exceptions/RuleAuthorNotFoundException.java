package ar.edu.utn.frba.arbiter.rules.exceptions;

/**
 * The user saving a new rule isn't in {@code arbiter_common.users}. Every rule records its creator,
 * so it can't be created without one.
 */
public class RuleAuthorNotFoundException extends RuntimeException {

    public RuleAuthorNotFoundException(String actorEmail) {
        super("No se encontró en Arbiter el usuario " + actorEmail + ": la regla necesita un autor");
    }
}
