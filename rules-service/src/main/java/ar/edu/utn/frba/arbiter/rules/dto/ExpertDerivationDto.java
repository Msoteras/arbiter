package ar.edu.utn.frba.arbiter.rules.dto;

import java.math.BigDecimal;

/**
 * Whether this insurer derives claims of a given branch to an external expert, and from what
 * amount. Read system-to-system by cases-service when the analyst opens a case.
 *
 * @param enabled          false when the insurer has no {@code EXPERT_DERIVATION} rule for the
 *                         branch, or has it inactive. Derivation is opt-in: an insurer that never
 *                         set a threshold doesn't derive.
 * @param minClaimedAmount null when {@code enabled} is false.
 * @param ruleId           the {@code insurer_rule} row behind the answer, for the audit trail.
 */
public record ExpertDerivationDto(boolean enabled, BigDecimal minClaimedAmount, Long ruleId) {

    public static ExpertDerivationDto disabled() {
        return new ExpertDerivationDto(false, null, null);
    }
}
