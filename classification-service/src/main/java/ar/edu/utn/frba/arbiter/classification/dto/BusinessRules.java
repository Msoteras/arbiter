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
        FraudRecordPolicy fraudRecordPolicy,
        List<EvaluableRule> evaluableRules,
        // Coverage limits evaluated by code; null means the corresponding rule isn't evaluated.
        Long reportDeadlineHours,
        Integer maxEventsPerYear,
        Integer waitingPeriodDays,
        // Taken from the referente's coverage config, not from the insurer DB's poliza.cubre_grupo_familiar.
        Boolean coversFamilyGroup,
        Boolean claimExhaustsCoverage
) {

    /**
     * A hard rule evaluated by code. {@code id} is what {@code rule_result.rule_id} points at.
     * Parameters are type-specific: {@code COVERAGE_EXCLUSION} matches claim causes by id (names
     * repeat across branches), {@code POLICE_DEADLINE} uses {@code deadlineHours}; the rest take
     * their thresholds from the coverage limits on the enclosing record.
     */
    @Builder
    public record EvaluableRule(
            Long id,
            String ruleType,
            String effect,
            boolean blocksFastTrack,
            List<Long> excludedClaimCauseIds,
            Long deadlineHours
    ) {}

    /**
     * Insurer-wide: how long a fraud record counts and whether it vetoes Fast Track. Whether it
     * scores is decided only by the {@link ScoringConfig}, so the two can't disagree.
     *
     * @param ruleId null when never configured, in which case the veto isn't evaluated
     */
    @Builder
    public record FraudRecordPolicy(
            Long ruleId,
            int windowMonths,
            boolean blocksFastTrack
    ) {

        public static final int DEFAULT_WINDOW_MONTHS = 36;

        /** A record still ages out when nothing is configured; it never counts forever. */
        public static FraudRecordPolicy unconfigured() {
            return new FraudRecordPolicy(null, DEFAULT_WINDOW_MONTHS, false);
        }
    }

    /** A null field means that criterion doesn't apply; null thresholds mean no Fast Track configured. */
    @Builder
    public record FastTrackThresholds(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            /** Null counts the insured's whole history. */
            Integer priorClaimsWindowMonths,
            Integer minPolicyAgeMonths,
            Boolean requiresUpToDatePolicy,
            List<String> requiredDocumentTypes
    ) {}

    /** Per-insurer fraud/risk score config: weighted factors and the bands the 0..1 score maps to. */
    @Builder
    public record ScoringConfig(
            /** Written to {@code cases.scoring_configuration_id}; null for the baseline, which isn't a row. */
            Long id,
            List<FactorWeight> factors,
            List<Band> bands,
            /**
             * Whether Fast Track claims still get OCR and the image-fraud cascade for a complete score.
             * Never gates Fast Track; it only decides how much analysis runs.
             */
            boolean fullAnalysisOnFastTrack
    ) {

        @Builder
        public record FactorWeight(String factorId, double weight) {}

        /** The highest matching band wins; a band at 0.0 is required so every score maps somewhere. */
        @Builder
        public record Band(RiskBand band, double minScoreInclusive) {}
    }
}
