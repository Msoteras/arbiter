package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Carries the {@code claims_analyst} id, not the user id, so it is only meaningful inside the tenant
 * that returned it. Reassigning overwrites the previous owner.
 */
public record AssignAnalystRequest(
        @NotNull(message = "analystId is required") Long analystId
) {}
