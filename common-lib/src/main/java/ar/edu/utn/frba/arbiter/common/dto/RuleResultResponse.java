package ar.edu.utn.frba.arbiter.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A rule as it was evaluated for a claim. Passes travel too — SSN 2/2023 audits which rule ran and
 * with what result, not only the rejections.
 *
 * @param evaluatedValue what the rule compared, verbatim ({@code "reportedAt=+29h max=72h"}) — it's
 *                       what lets the analyst check the verdict instead of trusting it
 */
public record RuleResultResponse(
        Long id,
        String ruleType,
        String result,
        String evaluatedValue,
        BigDecimal scoreContribution,
        Instant evaluatedAt
) {
}
