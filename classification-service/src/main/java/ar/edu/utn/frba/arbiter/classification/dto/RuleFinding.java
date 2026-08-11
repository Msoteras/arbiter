package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * El resultado de evaluar una regla dura contra un claim, listo para auditarse en
 * {@code rule_result} (Disposición SSN 2/2023: "qué regla se evaluó y con qué resultado"). Se
 * escribe tanto el PASS como el FAIL — una tabla que solo tiene rechazos no prueba que las demás
 * reglas se evaluaron.
 *
 * @param ruleId         id de la {@code insurer_rule} evaluada — va a {@code rule_result.rule_id}
 * @param ruleType       p.ej. {@code COVERAGE_EXCLUSION}
 * @param passed         {@code true} si la regla se satisface (no excluye); {@code false} si falla
 * @param evaluatedValue qué se comparó, legible (p.ej. {@code "claimCause=Hurto (id=3)"})
 */
public record RuleFinding(
        Long ruleId,
        String ruleType,
        boolean passed,
        String evaluatedValue
) {

    public String result() {
        return passed ? "PASS" : "FAIL";
    }
}
