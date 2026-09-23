package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * The deterministic Fast Track thresholds for one (branch, coverage). Mirrors
 * classification-service's {@code BusinessRules.FastTrackThresholds} field for field, since it's
 * serialized as-is into {@code insurer_rule.configuration}. A {@code null} field means "that
 * criterion doesn't apply"; an all-null config means no Fast Track configured.
 */
public record FastTrackConfigDto(
        @DecimalMin("0.0") @DecimalMax("1.0") Double maxClaimedAmountRatio,
        @Min(0) Integer maxPriorClaims,
        /** Window in months to count {@code maxPriorClaims}. Null = the insured's whole history. */
        @Min(0) Integer priorClaimsWindowMonths,
        /** Minimum policy age at the time of the event, in months. Null = not required. */
        @Min(0) Integer minPolicyAgeMonths,
        Boolean requiresUpToDatePolicy,
        List<String> requiredDocumentTypes,
        /**
         * The same criteria in Spanish, for the prompt. They decide nothing (the gate is the
         * thresholds above); they only describe the insurer's Fast Track policy to the LLM.
         */
        List<String> criteria
) {
    public static FastTrackConfigDto empty() {
        return new FastTrackConfigDto(null, null, null, null, null, null, null);
    }
}
