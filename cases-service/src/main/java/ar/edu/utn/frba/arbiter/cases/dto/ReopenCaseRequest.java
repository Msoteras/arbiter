package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a closed case is being reopened. Mandatory and free text: reopening overrides a decision
 * that was already communicated to the insured, and the reason is the only explanation that
 * survives in {@code case_status_history} for whoever audits the case later.
 */
public record ReopenCaseRequest(
        @NotBlank(message = "reason is required")
        // 200 and not 255: the reason is stored prefixed with "expediente reabierto: ", and
        // case_status_history.reason is a VARCHAR(255). Rejecting it here beats a truncation
        // error from the database on a write that already appended a history row.
        @Size(max = 200, message = "reason must be at most 200 characters")
        String reason
) {}
