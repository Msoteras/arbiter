package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The authenticated caller has no {@code claims_analyst} row in the current tenant, so there is
 * no id to attribute a decision to. {@code POST /cases/{id}/decision} allows both
 * ANALISTA_SINIESTROS and REFERENTE_ASEGURADORA (H0003), but only the former has a
 * claims_analyst profile — a referente hitting this means the role/profile mismatch needs a
 * product decision, not a silent 500.
 */
public class AnalystProfileNotFoundException extends RuntimeException {

    public AnalystProfileNotFoundException(String email) {
        super("No hay un perfil de analista para " + email + " en esta aseguradora");
    }
}
