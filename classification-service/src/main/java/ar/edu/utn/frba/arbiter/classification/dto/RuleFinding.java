package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * A hard-rule evaluation for {@code rule_result}; both PASS and FAIL are audited.
 *
 * @param ruleId         null for coverage-scope rules, which are coverage columns rather than {@code insurer_rule} rows
 * @param evaluatedValue human-readable input, e.g. {@code "claimCause=Hurto (id=3)"}
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
