package ar.edu.utn.frba.arbiter.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A rule as evaluated for a claim. Passes travel too: SSN 2/2023 audits every rule that ran.
 *
 * @param evaluatedValue what the rule compared, verbatim (e.g. {@code "reportedAt=+29h max=72h"})
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
