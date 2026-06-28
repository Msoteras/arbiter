package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record BusinessRules(
        String branchId,
        String claimCauseId,
        List<String> rules,
        List<String> exclusions,
        List<String> fastTrackCriteria,
        FastTrackThresholds fastTrackThresholds
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
}
