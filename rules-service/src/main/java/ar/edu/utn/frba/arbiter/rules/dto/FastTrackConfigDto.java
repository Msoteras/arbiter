package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * The deterministic Fast Track thresholds a referente configures for one (rama, cobertura).
 * Mirrors classification-service's {@code BusinessRules.FastTrackThresholds} field for field —
 * this is exactly what gets serialized into {@code insurer_rule.configuration} (JSONB) and what
 * classification will read back to gate Fast Track. A {@code null} field means "that criterion
 * doesn't apply"; an all-null config means the coverage has no Fast Track configured.
 */
public record FastTrackConfigDto(
        @DecimalMin("0.0") @DecimalMax("1.0") Double maxClaimedAmountRatio,
        @Min(0) Integer maxPriorClaims,
        /**
         * Window in months to count {@code maxPriorClaims}. Null = no window, i.e. the insured's
         * whole history — which was the previous behaviour, when the field existed only in the UI
         * and got dropped: "at most 1 prior claim" meant *never in their life*, not *in the last
         * 24 months* (D14).
         */
        @Min(0) Integer priorClaimsWindowMonths,
        /** Minimum policy age at the time of the event, in months. Null = not required. */
        @Min(0) Integer minPolicyAgeMonths,
        Boolean requiresUpToDatePolicy,
        List<String> requiredDocumentTypes,
        /**
         * The same criteria in Spanish, for the LLM to read. They decide nothing — the gate is the
         * thresholds above — but they travel to the prompt as a description of the insurer's Fast
         * Track policy. They used to come hardcoded from {@code MockRulesAdapter}, so the text
         * could contradict the numbers the referente configured (D14).
         */
        List<String> criteria
) {
    public static FastTrackConfigDto empty() {
        return new FastTrackConfigDto(null, null, null, null, null, null, null);
    }
}
