package ar.edu.utn.frba.arbiter.rules.dto;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import jakarta.validation.constraints.Min;

/**
 * A hard temporal rule as the referente sees and edits it in the panel: whether it's active and,
 * for the one that carries it, its threshold. The rest of the thresholds don't travel here because
 * they don't live here — they're {@code coverage} columns edited from the Coverages tab.
 *
 * @param ruleType      which hard rule (see {@link RuleType#temporalRules()})
 * @param enabled       whether the insurer has it active. {@code false} ⇒ the engine doesn't evaluate it
 * @param deadlineHours threshold in hours, only for {@code POLICE_DEADLINE}; {@code null} otherwise
 */
public record HardRuleDto(
        RuleType ruleType,
        boolean enabled,
        @Min(0) Long deadlineHours
) {

    /** The rule turned off — how an insurer that never configured it behaves. */
    public static HardRuleDto disabled(RuleType ruleType) {
        return new HardRuleDto(ruleType, false, null);
    }
}
