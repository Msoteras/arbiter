package ar.edu.utn.frba.arbiter.rules.dto;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

/** A persisted row of the document schedule — confirmation of what landed in the DB. */
public record DocumentRequirementDto(Long id, String documentType, Long claimCauseId, RiskBand minRiskBand) {}
