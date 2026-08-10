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
        ScoringConfig scoringConfig,
        List<EvaluableRule> evaluableRules,
        // Límites intrínsecos de la cobertura (columnas de coverage), evaluables por código:
        // plazo de denuncia (D11) y tope de eventos por año (D10). null = no configurado ⇒ la regla
        // correspondiente no se evalúa.
        Long reportDeadlineHours,
        Integer maxEventsPerYear
) {

    /**
     * A hard rule evaluated by code (not interpreted by the LLM), configured by the insurer.
     * {@code id} is the {@code insurer_rule} id and must survive the trip: it's what
     * {@code rule_result.rule_id} points at, and without it there's no audit trail
     * (Disposición SSN 2/2023). Today the only {@code ruleType} is {@code COVERAGE_EXCLUSION}:
     * the evaluator matches the claim's hecho generador against {@code excludedClaimCauseIds}
     * <b>by id</b> (names repeat across branches; the id is unambiguous).
     */
    @Builder
    public record EvaluableRule(
            Long id,
            String ruleType,
            String effect,
            boolean blocksFastTrack,
            List<Long> excludedClaimCauseIds
    ) {}

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
