package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Analyst the case is handed over to, as the {@code claims_analyst} id — not the user id, and
 * only meaningful inside the tenant that returned it. One analyst at a time: reassigning
 * overwrites the previous owner instead of adding a second one.
 */
public record AssignAnalystRequest(
        @NotNull(message = "analystId is required") Long analystId
) {}
