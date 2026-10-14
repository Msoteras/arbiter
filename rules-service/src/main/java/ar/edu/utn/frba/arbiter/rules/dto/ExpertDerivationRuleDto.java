package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * A branch's derivation-to-expert rule as the referente sees and edits it. Unlike
 * {@link ExpertDerivationDto}, the amount travels even when the rule is off, so turning it back on
 * doesn't lose the threshold.
 *
 * @param enabled          whether the analyst may derive claims of this branch to an expert
 * @param minClaimedAmount the claimed amount it applies from; required when {@code enabled}
 */
public record ExpertDerivationRuleDto(
        boolean enabled,
        @DecimalMin("0") BigDecimal minClaimedAmount
) {

    /** How a branch that never configured the rule looks in the panel. */
    public static ExpertDerivationRuleDto unconfigured() {
        return new ExpertDerivationRuleDto(false, null);
    }
}
