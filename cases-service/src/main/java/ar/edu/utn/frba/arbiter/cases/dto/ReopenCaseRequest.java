package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mandatory: reopening overrides a decision already communicated to the insured, and this is the only
 * explanation left in {@code case_status_history} for later audits.
 */
public record ReopenCaseRequest(
        @NotBlank(message = "reason is required")
        // 200, not 255: it's stored with a prefix in case_status_history.reason, a VARCHAR(255).
        @Size(max = 200, message = "reason must be at most 200 characters")
        String reason
) {}
