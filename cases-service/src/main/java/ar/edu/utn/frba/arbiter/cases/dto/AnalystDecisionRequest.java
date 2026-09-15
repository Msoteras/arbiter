package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * @param analystId              {@code claims_analyst.id} of whoever is deciding. Numeric because
 *                               {@code case_classification.analyst_id} is a real FK. Never sent by
 *                               the frontend (a client-supplied id would let anyone attribute a
 *                               decision to a different analyst) — {@code CaseServiceImpl} resolves
 *                               it from the caller's JWT before forwarding, the side that already
 *                               knows the user.
 * @param justification          the analyst's stated reason for the decision — the audit trail
 *                               Disposición SSN 2/2023 requires. Always mandatory, agreeing with
 *                               the model or not: the paper (§2.2) requires every decision to be
 *                               explicit and justified, not just the ones that override the
 *                               recommendation. Persisted verbatim on {@code
 *                               case_classification.analyst_justification}.
 * @param classificationAttempts how many classification attempts the case took before landing on
 *                               the analyst's desk. The live counter is {@code
 *                               cases.classification_attempts} (the poller uses it to give up and
 *                               mark CLASSIFICATION_FAILED); its final value gets frozen onto the
 *                               auditable row here. Not sent by the frontend — cases-service fills
 *                               it in from the case when it forwards the decision, same as
 *                               {@code analystId}.
 * @param settlement             how much gets paid, required when approving and rejected when
 *                               rejecting. Determining the amount is a step of the analyst's job
 *                               (NSIN001 §5.2.1.2), not a follow-up: approving without saying how
 *                               much leaves the company with a claim it owes an unknown sum on.
 *                               Never forwarded to classification-service — that module audits the
 *                               verdict, and the money is cases-service's own record.
 */
public record AnalystDecisionRequest(
        Long analystId,
        @NotBlank(message = "decision is required") String decision,
        @NotBlank(message = "justification is required") String justification,
        Integer classificationAttempts,
        @Valid SettlementDecisionRequest settlement
) {}
