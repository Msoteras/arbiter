package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The insurer's resolution target: a management goal, not the legal deadline (that one lives per
 * case in {@code cases.response_deadline}). It's configuration, not a rule: the engine never
 * evaluates it and it leaves no {@code rule_result}; only the dashboard reads it.
 *
 * @param enabled    false hides the comparison on the dashboard instead of using a made-up number.
 * @param targetDays days from the claim report to the decision.
 */
public record ResolutionTargetDto(
        boolean enabled,
        // A 0-day target can never be met; 365 matches the dashboard's maximum period.
        @Min(1) @Max(365) Integer targetDays
) {

    public static ResolutionTargetDto unset() {
        return new ResolutionTargetDto(false, null);
    }
}
