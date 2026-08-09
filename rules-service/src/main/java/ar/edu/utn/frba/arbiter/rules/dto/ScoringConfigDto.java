package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.Valid;

import java.util.List;

/** Configuración de scoring de fraude de un ramo — mirrors classification-service's BusinessRules.ScoringConfig. */
public record ScoringConfigDto(
        boolean enabled,
        @Valid List<FactorWeightDto> factors,
        @Valid List<ScoreBandCutDto> bands) {

    public static ScoringConfigDto empty() {
        return new ScoringConfigDto(false, List.of(), List.of());
    }
}
