package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The caller has no {@code claims_analyst} row in the current tenant, so a decision can't be
 * attributed. Referentes may call the decision endpoint but have no such profile.
 */
public class AnalystProfileNotFoundException extends RuntimeException {

    public AnalystProfileNotFoundException(String email) {
        super("No hay un perfil de analista para " + email + " en esta aseguradora");
    }
}
