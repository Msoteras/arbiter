package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage exclusions by claim cause id, evaluated in code before the Fast Track gate. An exclusion
 * blocks Fast Track and routes to review; it never decides the case.
 */
@Service
public class CoverageRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageRuleEvaluator.class);

    public record Result(boolean excluded, List<RuleFinding> findings) {}

    public Result evaluate(ClaimReport claim, BusinessRules rules) {
        List<BusinessRules.EvaluableRule> evaluableRules = rules.evaluableRules();
        if (evaluableRules == null || evaluableRules.isEmpty()) {
            return new Result(false, List.of());
        }

        List<RuleFinding> findings = new ArrayList<>();
        boolean excluded = false;

        for (BusinessRules.EvaluableRule rule : evaluableRules) {
            if (!RuleType.COVERAGE_EXCLUSION.name().equals(rule.ruleType())) {
                continue;
            }
            // The referente can save an exclusion with no claim causes: nothing to evaluate or audit.
            if (rule.excludedClaimCauseIds() == null || rule.excludedClaimCauseIds().isEmpty()) {
                continue;
            }
            boolean causeExcluded = isExcludedBy(rule, claim.claimCauseId());
            // PASS = covered; FAIL = excluded.
            findings.add(new RuleFinding(
                    rule.id(),
                    rule.ruleType(),
                    !causeExcluded,
                    "claimCause=" + claim.claimCause() + " (id=" + claim.claimCauseId() + ")"));
            if (causeExcluded) {
                excluded = true;
            }
        }

        if (excluded) {
            log.info("[CoverageRuleEvaluator] Claim cause '{}' (id={}) excluded by coverage {}: blocks Fast Track",
                    claim.claimCause(), claim.claimCauseId(), claim.coverageId());
        }
        return new Result(excluded, findings);
    }

    /**
     * Lets the cause-consistency pass ask about the cause the account describes: the model says which
     * cause it is, the engine says whether it's covered. A null id is never excluded.
     */
    public boolean isExcluded(Long claimCauseId, BusinessRules rules) {
        List<BusinessRules.EvaluableRule> evaluableRules = rules.evaluableRules();
        if (claimCauseId == null || evaluableRules == null) {
            return false;
        }
        return evaluableRules.stream()
                .filter(rule -> RuleType.COVERAGE_EXCLUSION.name().equals(rule.ruleType()))
                .anyMatch(rule -> isExcludedBy(rule, claimCauseId));
    }

    private boolean isExcludedBy(BusinessRules.EvaluableRule rule, Long claimCauseId) {
        return claimCauseId != null
                && rule.excludedClaimCauseIds() != null
                && rule.excludedClaimCauseIds().contains(claimCauseId);
    }

    public List<String> excludedReasons(Result result, ClaimReport claim) {
        return result.findings().stream()
                .filter(f -> !f.passed())
                .map(f -> "La cobertura no cubre el hecho generador declarado ("
                        + claim.claimCause() + ") — exclusión configurada por la aseguradora")
                .toList();
    }
}
