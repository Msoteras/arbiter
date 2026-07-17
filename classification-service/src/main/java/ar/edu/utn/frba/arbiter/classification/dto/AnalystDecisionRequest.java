package ar.edu.utn.frba.arbiter.classification.dto;

import jakarta.validation.constraints.NotBlank;

public record AnalystDecisionRequest(
        @NotBlank(message = "analystId is required") String analystId,
        @NotBlank(message = "decision is required") String decision
) {}
