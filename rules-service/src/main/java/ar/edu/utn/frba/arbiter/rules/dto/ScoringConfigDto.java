package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.Valid;

import java.util.List;

/** Configuración de scoring de fraude de un ramo — mirrors classification-service's BusinessRules.ScoringConfig. */
public record ScoringConfigDto(
        /**
         * La fila de {@code scoring_configuration}. Sale del servidor en las lecturas y se
         * <b>ignora</b> en el guardado (hay una sola config por aseguradora, se busca por sí misma).
         * Viaja para que el motor pueda anotar en el expediente con qué configuración calculó el
         * score (D29): sin eso, un score auditado no se puede explicar después de que el referente
         * cambie los pesos.
         */
        Long id,
        boolean enabled,
        /**
         * Si el Fast Track de esta aseguradora igual corre el análisis pesado (OCR + fraude de
         * imágenes) para que el score salga completo. Default false: el Fast Track queda rápido y el
         * score es parcial. No vetea el Fast Track — solo decide cuánto análisis corre.
         */
        boolean fullAnalysisOnFastTrack,
        @Valid List<FactorWeightDto> factors,
        @Valid List<ScoreBandCutDto> bands) {

    public static ScoringConfigDto empty() {
        return new ScoringConfigDto(null, false, false, List.of(), List.of());
    }
}
