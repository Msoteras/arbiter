package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * rules-service answered, but refused: a 4xx, typically a configuration it can't read. A subtype of
 * {@link RulesUnavailableException} so every place that carries on without rules still does; only
 * what retries by itself tells them apart, since this one won't fix itself until the referent does.
 */
public class RulesMisconfiguredException extends RulesUnavailableException {

    public RulesMisconfiguredException(Throwable cause) {
        super(cause);
    }

    @Override
    public String getMessage() {
        return "La configuración de reglas de la aseguradora no se puede leer. Avisale al referente.";
    }
}
