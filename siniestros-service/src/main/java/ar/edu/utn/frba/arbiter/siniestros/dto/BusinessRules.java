package ar.edu.utn.frba.arbiter.siniestros.dto;

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
     * Umbrales evaluables de forma determinística (sin LLM) para decidir Fast Track.
     * {@code null} en cualquier campo significa "ese criterio no aplica para este ramo/hecho generador".
     * Si {@code fastTrackThresholds} es null, el ramo/hecho generador no tiene Fast Track configurado.
     */
    @Builder
    public record FastTrackThresholds(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            Boolean requiresUpToDatePolicy,
            List<String> requiredDocumentTypes
    ) {}
}
