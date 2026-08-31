package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * The result of evaluating a hard rule against a claim, ready to be audited in
 * {@code rule_result} (SSN Disposition 2/2023: "which rule was evaluated and with what result").
 * Both PASS and FAIL are written — a table holding only rejections doesn't prove the other rules
 * were evaluated.
 *
 * @param ruleId         id of the evaluated {@code insurer_rule} — goes to {@code rule_result.rule_id}.
 *                       Null for the coverage-scope rules ({@code COVERS_FAMILY_GROUP},
 *                       {@code CLAIM_EXHAUSTS_COVERAGE}): those are coverage columns, not rows of
 *                       {@code insurer_rule}, so there's no id to point at
 * @param ruleType       e.g. {@code COVERAGE_EXCLUSION}
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
