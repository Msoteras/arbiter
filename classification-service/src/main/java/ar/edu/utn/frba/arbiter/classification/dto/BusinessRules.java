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
        Integer maxEventsPerYear,
        // Carencia (D9): días desde el alta de la póliza en que la cobertura todavía no aplica.
        Integer waitingPeriodDays,
        // Alcance de la cobertura (D9). La fuente es la cobertura que configura el referente, no
        // poliza.cubre_grupo_familiar de la BD Aseguradora — las dos existen y ya se contradicen.
        Boolean coversFamilyGroup,
        Boolean claimExhaustsCoverage
) {

    /**
     * A hard rule evaluated by code (not interpreted by the LLM), configured by the insurer.
     * {@code id} is the {@code insurer_rule} id and must survive the trip: it's what
     * {@code rule_result.rule_id} points at, and without it there's no audit trail
     * (Disposición SSN 2/2023). Today the only {@code ruleType} is {@code COVERAGE_INCLUSION}:
     * the evaluator matches the claim's hecho generador against {@code includedClaimCauseIds}
     * <b>by id</b> (names repeat across branches; the id is unambiguous) — not in the list means
     * the coverage doesn't cover it.
     */
    @Builder
    public record EvaluableRule(
            Long id,
            String ruleType,
            String effect,
            boolean blocksFastTrack,
            List<Long> includedClaimCauseIds
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
             * Ventana en meses sobre la que se cuenta {@code maxPriorClaims}. Null = histórico
             * completo, que es como se comportaba antes de existir el campo: el límite se
             * comparaba contra los siniestros de toda la vida del asegurado (D14).
             */
            Integer priorClaimsWindowMonths,
            /** Antigüedad mínima de la póliza al momento del hecho, en meses. Null = no se exige. */
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
             * Fila de {@code scoring_configuration} de la que salió esta config. Viaja para poder
             * escribir {@code cases.scoring_configuration_id}: sin eso, un score auditado no dice
             * con qué configuración se calculó, y el referente puede haberla cambiado desde
             * entonces (D29). Null cuando el scoring es el baseline del mock, que no es una fila.
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
