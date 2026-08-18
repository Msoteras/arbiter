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
        // The coverage's intrinsic limits (coverage columns), evaluable by code: report deadline
        // (D11) and events-per-year cap (D10). null = not configured ⇒ the corresponding rule
        // isn't evaluated.
        Long reportDeadlineHours,
        Integer maxEventsPerYear,
        // Waiting period (D9): days since the policy's start date during which the coverage still
        // doesn't apply.
        Integer waitingPeriodDays,
        // Coverage scope (D9). The source is the coverage the referente configures, not
        // poliza.cubre_grupo_familiar from the insurer DB — the two exist and already contradict
        // each other.
        Boolean coversFamilyGroup,
        Boolean claimExhaustsCoverage
) {

    /**
     * A hard rule evaluated by code (not interpreted by the LLM), configured by the insurer.
     * {@code id} is the {@code insurer_rule} id and must survive the trip: it's what
     * {@code rule_result.rule_id} points at, and without it there's no audit trail
     * (Disposición SSN 2/2023).
     *
     * <p>The parameters are nullable because a single list carries different types
     * ({@link ar.edu.utn.frba.arbiter.common.enums.RuleType}): {@code COVERAGE_EXCLUSION} matches
     * the claim's hecho generador against {@code excludedClaimCauseIds} <b>by id</b> (names repeat
     * across branches, the id doesn't), and {@code POLICE_DEADLINE} carries its threshold in
     * {@code deadlineHours}. The other hard rules arrive <b>with no parameters on purpose</b>: the
     * row only says the insurer has the rule active, and the coverage sets the threshold (see
     * {@code reportDeadlineHours}, {@code maxEventsPerYear}, {@code waitingPeriodDays} on this same
     * record).
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
     * Deterministically evaluable thresholds (no LLM) to decide Fast Track.
     * {@code null} in any field means "that criterion doesn't apply for this branch/claim cause".
     * If {@code fastTrackThresholds} is null, the branch/claim cause has no Fast Track configured.
     */
    @Builder
    public record FastTrackThresholds(
            Double maxClaimedAmountRatio,
            Integer maxPriorClaims,
            /**
             * Window in months over which {@code maxPriorClaims} is counted. Null = the whole
             * history, which is how it behaved before the field existed: the limit was compared
             * against the insured's lifetime claims (D14).
             */
            Integer priorClaimsWindowMonths,
            /** Minimum policy age at the time of the event, in months. Null = not required. */
            Integer minPolicyAgeMonths,
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
            /**
             * The {@code scoring_configuration} row this config came from. It travels so
             * {@code cases.scoring_configuration_id} can be written: without it, an audited score
             * doesn't say which configuration computed it, and the referente may have changed it
             * since (D29). Null when the scoring is the mock baseline, which isn't a row.
             */
            Long id,
            List<FactorWeight> factors,
            List<Band> bands,
            /**
             * Whether Fast Track claims still get the full (heavy) analysis — OCR of every attachment
             * plus the image-fraud cascade — so their fraud score comes out complete instead of only
             * on structured-data factors. {@code false} (default) keeps Fast Track fast: it skips that
             * analysis and the score is partial. Per-insurer, configured by the referente: some want
             * the expedited lane to stay quick, others want the fraud read done regardless. It never
             * gates Fast Track — the score is a parallel signal — it only decides how much runs.
             */
            boolean fullAnalysisOnFastTrack
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
