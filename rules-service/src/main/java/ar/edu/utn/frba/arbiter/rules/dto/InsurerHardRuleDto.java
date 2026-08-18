package ar.edu.utn.frba.arbiter.rules.dto;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import jakarta.validation.constraints.Pattern;

/**
 * A hard temporal rule scoped to the whole insurer, as the referente sees and edits it: whether
 * it's active and, only for {@code POLICY_STANDING}, what happens when a policy is in arrears.
 *
 * @param ruleType   {@code POLICY_IN_FORCE} or {@code POLICY_STANDING} (see
 *                   {@link RuleType#insurerScoped()})
 * @param enabled    whether the insurer has it active. {@code false} ⇒ the engine doesn't evaluate it
 * @param onArrears  {@code REJECT} (cases-service rejects the denuncia at intake, no expediente is
 *                   created) or {@code STANDBY} (the expediente is created and the engine derives
 *                   it to the analyst during classification, same as before this rule existed).
 *                   Only meaningful for {@code POLICY_STANDING}; {@code null} for
 *                   {@code POLICY_IN_FORCE}, which has no such choice — coverage window is never
 *                   a business policy call.
 */
public record InsurerHardRuleDto(
        RuleType ruleType,
        boolean enabled,
        @Pattern(regexp = "REJECT|STANDBY") String onArrears
) {

    public static final String ON_ARREARS_REJECT = "REJECT";
    public static final String ON_ARREARS_STANDBY = "STANDBY";

    /** The rule turned off — how an insurer that never configured it behaves. */
    public static InsurerHardRuleDto disabled(RuleType ruleType) {
        return new InsurerHardRuleDto(
                ruleType, false, ruleType == RuleType.POLICY_STANDING ? ON_ARREARS_STANDBY : null);
    }
}
