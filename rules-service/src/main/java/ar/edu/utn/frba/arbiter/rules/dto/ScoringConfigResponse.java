package ar.edu.utn.frba.arbiter.rules.dto;

/** Save confirmation: the {@code scoring_configuration} row and its config as persisted. */
public record ScoringConfigResponse(Long id, ScoringConfigDto config) {}
