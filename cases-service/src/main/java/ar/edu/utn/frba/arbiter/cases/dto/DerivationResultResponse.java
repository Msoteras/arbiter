package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;

import java.time.Instant;

public record DerivationResultResponse(
        ProviderType providerType,
        ExpertVerdict verdict,
        RepairOutcome repairOutcome,
        Instant respondedAt) {
}
