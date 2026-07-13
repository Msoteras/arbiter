package ar.edu.utn.frba.arbiter.common.dto;

import lombok.Builder;

/**
 * One factor's share of a claim's fraud/risk score, shared so the same breakdown travels the
 * classification→cases channel and is rendered by the analyst's fraud-gauge. Mirrors the
 * classification-service's internal {@code RiskScore.FactorBreakdown}.
 *
 * @param rawScore             the evaluator's normalized contribution in [0.0, 1.0]
 * @param weight               the insurer's weight for this factor
 * @param weightedContribution {@code rawScore * weight} — this factor's absolute push on the total
 */
@Builder
public record RiskBreakdownItem(
        String factorId,
        double rawScore,
        double weight,
        double weightedContribution,
        String rationale
) {}
