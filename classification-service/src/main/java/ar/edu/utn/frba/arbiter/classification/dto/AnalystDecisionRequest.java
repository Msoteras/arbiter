package ar.edu.utn.frba.arbiter.classification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param analystId              {@code claims_analyst.id} of whoever is deciding. Numeric because
 *                               {@code case_classification.analyst_id} is a real FK —
 *                               cases-service resolves it from the caller's JWT, which is the side
 *                               that already knows the user, so by the time it reaches here it's
 *                               always populated.
 * @param justification          the analyst's stated reason for the decision — the audit trail
 *                               Disposición SSN 2/2023 requires. Persisted verbatim on {@code
 *                               case_classification.analyst_justification}.
 * @param classificationAttempts how many attempts the case took before reaching the analyst,
 *                               carried over from {@code cases.classification_attempts} so the
 *                               auditable row keeps it. Null when the caller doesn't know it.
 */
public record AnalystDecisionRequest(
        @NotNull(message = "analystId is required") Long analystId,
        @NotBlank(message = "decision is required") String decision,
        String justification,
        Integer classificationAttempts
) {}
