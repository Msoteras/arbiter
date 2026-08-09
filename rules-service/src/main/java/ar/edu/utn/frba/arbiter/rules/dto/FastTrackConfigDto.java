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
        Boolean requiresUpToDatePolicy,
        List<String> requiredDocumentTypes
) {
    public static FastTrackConfigDto empty() {
        return new FastTrackConfigDto(null, null, null, null);
    }
}
