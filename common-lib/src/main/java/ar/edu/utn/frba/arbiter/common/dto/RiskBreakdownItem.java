package ar.edu.utn.frba.arbiter.common.dto;

import lombok.Builder;

/**
 * One factor's share of a claim's fraud/risk score. Mirrors classification-service's
 * {@code RiskScore.FactorBreakdown}.
 *
 * @param rawScore             normalized contribution in [0.0, 1.0]
 * @param weightedContribution {@code rawScore * weight}
 */
@Builder
public record RiskBreakdownItem(
        String factorId,
        double rawScore,
        double weight,
        double weightedContribution,
        String rationale
) {}
