package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;

/**
 * Everything a risk evaluator needs to grade a claim, assembled from the SAME data the
 * {@code ClassificationOrchestrator} already fetched for the classification flow (the claim
 * plus the insured's policy, history and business rules). The score is computed downstream of
 * the classification off this context — no evaluator hits {@code InsurerAdapter} again.
 */
public record RiskContext(
        ClaimReport claim,
        InsuredPolicy policy,
        InsuredHistory history,
        BusinessRules rules
) {}
