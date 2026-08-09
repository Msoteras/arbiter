package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record BusinessRules(
        String branchId,
        String claimCauseId,
        List<String> rules,
        List<String> exclusions,
        List<String> fastTrackCriteria,
        FastTrackThresholds fastTrackThresholds,
        List<String> requiredDocumentTypes,
        ScoringConfig scoringConfig
) {

    /**
     * Deterministically evaluable thresholds (no LLM) to decide Fast Track.
     * {@code null} in any field means "that criterion doesn't apply for this branch/claim cause".
     * If {@code fastTrackThresholds} is null, the branch/claim cause has no Fast Track configured.
     */
    @Builder
    public record FastTrackThresholds(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            Boolean requiresUpToDatePolicy,
            List<String> requiredDocumentTypes
    ) {}

    /**
     * Per-insurer configuration of the parallel fraud/risk score. Fully data-driven so a new
     * insurer is onboarded by config, not code: which factors count ({@code factors}, each with
     * its weight) and how the resulting 0..1 score maps to a {@link RiskBand} ({@code bands}).
     * If {@code scoringConfig} is null, the branch/claim cause has no risk scoring configured.
     */
    @Builder
    public record ScoringConfig(
            List<FactorWeight> factors,
            List<Band> bands
    ) {

        /** A risk factor that is active for this insurer, and how much it weighs in the sum. */
        @Builder
        public record FactorWeight(String factorId, double weight) {}

        /**
         * A band applies when the normalized score is {@code >= minScoreInclusive}; the resolver
         * picks the highest matching band. There must be a band with {@code minScoreInclusive = 0.0}
         * so every score maps somewhere.
         */
        @Builder
        public record Band(RiskBand band, double minScoreInclusive) {}
    }
}
