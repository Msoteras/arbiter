package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param reason mandatory: without it the analyst gets a stalled case and no clue about what to fix
 */
public record SettlementReturnRequest(
        @NotBlank(message = "reason is required") String reason
) {}
