package ar.edu.utn.frba.arbiter.classification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param analystId              {@code claims_analyst.id}, resolved by cases-service from the caller's JWT
 * @param justification          mandatory even when agreeing with the model: required for the audit trail
 * @param classificationAttempts copied from {@code cases.classification_attempts}; may be null
 */
public record AnalystDecisionRequest(
        @NotNull(message = "analystId is required") Long analystId,
        @NotBlank(message = "decision is required") String decision,
        @NotBlank(message = "justification is required") String justification,
        Integer classificationAttempts
) {}
