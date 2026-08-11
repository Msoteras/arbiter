package ar.edu.utn.frba.arbiter.rules.dto;

/** Una fila persistida de la agenda documental — confirmación de lo que quedó en la DB. */
public record DocumentRequirementDto(Long id, String documentType, Long claimCauseId, boolean mandatory) {}
