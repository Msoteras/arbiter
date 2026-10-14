package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;
import java.util.Objects;

/**
 * A branch's derivation-to-repair rule as the referente sees and edits it. Unlike
 * {@link RepairDerivationDto}, the claim causes travel even when the rule is off, so turning it
 * back on doesn't lose the selection.
 *
 * @param enabled       whether the analyst may derive claims of this branch to a repair shop
 * @param claimCauseIds the claim causes of the branch it applies to; at least one when {@code enabled}
 */
public record RepairDerivationRuleDto(boolean enabled, List<Long> claimCauseIds) {

    public RepairDerivationRuleDto {
        claimCauseIds = claimCauseIds == null
                ? List.of()
                : claimCauseIds.stream().filter(Objects::nonNull).toList();
    }

    /** How a branch that never configured the rule looks in the panel. */
    public static RepairDerivationRuleDto unconfigured() {
        return new RepairDerivationRuleDto(false, List.of());
    }
}
