package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * @param analystId              never sent by the frontend: resolved from the caller's JWT so nobody
 *                               can attribute a decision to another analyst
 * @param justification          mandatory on every decision, agreeing with the model or not
 *                               (SSN Disposition 2/2023 audit trail)
 * @param classificationAttempts filled in by cases-service from the case, frozen onto the audit row
 * @param settlement             required when approving, rejected when rejecting. Never forwarded to
 *                               classification-service, which audits the verdict, not the money
 */
public record AnalystDecisionRequest(
        Long analystId,
        @NotBlank(message = "decision is required") String decision,
        @NotBlank(message = "justification is required") String justification,
        Integer classificationAttempts,
        @Valid SettlementDecisionRequest settlement
) {}
