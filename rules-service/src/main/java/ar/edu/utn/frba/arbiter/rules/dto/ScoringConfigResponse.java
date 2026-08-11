package ar.edu.utn.frba.arbiter.rules.dto;

/** Confirmación de guardado: la fila de {@code scoring_configuration} + su config, tal como quedó en la DB. */
public record ScoringConfigResponse(Long id, ScoringConfigDto config) {}
