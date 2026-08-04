package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param analystId              {@code claims_analyst.id} of whoever is deciding. Numeric because
 *                               {@code case_classification.analyst_id} is a real FK —
 *                               cases-service resolves it from the caller's JWT, which is the side
 *                               that already knows the user.
 * @param classificationAttempts how many classification attempts the case took before landing on
 *                               the analyst's desk. The live counter is {@code
 *                               cases.classification_attempts} (the poller uses it to give up and
 *                               mark CLASSIFICATION_FAILED); its final value gets frozen onto the
 *                               auditable row here. Not sent by the frontend — cases-service fills
 *                               it in from the case when it forwards the decision, same as
 *                               {@code analystId}.
 */
public record AnalystDecisionRequest(
        @NotNull(message = "analystId is required") Long analystId,
        @NotBlank(message = "decision is required") String decision,
        Integer classificationAttempts
) {}
