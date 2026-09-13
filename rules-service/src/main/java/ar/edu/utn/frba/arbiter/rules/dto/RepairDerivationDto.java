package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Which claim causes of a branch this insurer derives to a repair shop. Read system-to-system by
 * cases-service when the analyst opens a case.
 *
 * @param enabled       false when the insurer has no active {@code REPAIR_DERIVATION} rule for the
 *                      branch. Opt-in, like peritaje.
 * @param claimCauseIds empty when {@code enabled} is false.
 * @param ruleId        the {@code insurer_rule} row behind the answer, for the audit trail.
 */
public record RepairDerivationDto(boolean enabled, List<Long> claimCauseIds, Long ruleId) {

    public static RepairDerivationDto disabled() {
        return new RepairDerivationDto(false, List.of(), null);
    }
}
