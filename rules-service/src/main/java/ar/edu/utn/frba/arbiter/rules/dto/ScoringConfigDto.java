package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.Valid;

import java.util.List;

/** A branch's fraud scoring config — mirrors classification-service's BusinessRules.ScoringConfig. */
public record ScoringConfigDto(
        /**
         * The {@code scoring_configuration} row. The server sends it on reads and <b>ignores</b> it
         * on save (there's a single config per insurer, found on its own). It travels so the engine
         * can record on the case which configuration computed the score (D29): without it, an
         * audited score can't be explained once the referente changes the weights.
         */
        Long id,
        boolean enabled,
        /**
         * Whether this insurer's Fast Track still runs the heavy analysis (OCR + image fraud) so
         * the score comes out complete. Defaults to false: Fast Track stays fast and the score is
         * partial. It doesn't veto Fast Track — it only decides how much analysis runs.
         */
        boolean fullAnalysisOnFastTrack,
        @Valid List<FactorWeightDto> factors,
        @Valid List<ScoreBandCutDto> bands) {

    public static ScoringConfigDto empty() {
        return new ScoringConfigDto(null, false, false, List.of(), List.of());
    }
}
