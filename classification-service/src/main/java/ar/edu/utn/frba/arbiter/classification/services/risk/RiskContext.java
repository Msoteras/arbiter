package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;

/**
 * Everything a risk evaluator needs to grade a claim, assembled from the SAME data the
 * {@code ClassificationOrchestrator} already fetched for the classification flow (the claim
 * plus the insured's policy, history and business rules). The score is computed downstream of
 * the classification off this context — no evaluator hits {@code InsurerAdapter} again.
 *
 * <p>{@code imageFraud} carries the pre-computed image-fraud analysis (the cascade already ran
 * once, off the same flow). Null when it didn't run (Fast Track, or no images) — the image
 * evaluators treat that as "not evaluable" and contribute 0.
 */
public record RiskContext(
        ClaimReport claim,
        InsuredPolicy policy,
        InsuredHistory history,
        BusinessRules rules,
        ImageForensicReport imageFraud
) {

    /** No image analysis available (isolated flows, or when the cascade didn't run). */
    public RiskContext(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        this(claim, policy, history, rules, null);
    }
}
