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
 * Deterministic evaluation of the hard coverage rules — today, the <b>exclusions</b>: which claim
 * causes the claim's coverage does NOT cover. It runs <b>before</b> the Fast Track gate (a hard
 * exclusion makes Fast Track irrelevant) and without the LLM: it's code comparing ids, not model
 * interpretation. It closes the handoff's D3 (nothing validated the claim cause was covered) and,
 * together with writing {@code rule_result}, D4c.
 *
 * <p><b>It doesn't decide the case.</b> It produces a finding, not a resolution: an exclusion blocks
 * Fast Track and routes to review, but the decision is still the analyst's (CLAUDE.md #5,
 * human-in-the-loop). The result is audited in {@code rule_result} on both PASS and FAIL.
 */
@Service
public class CoverageRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageRuleEvaluator.class);

    /**
     * @param excluded {@code true} if any hard exclusion applies to the claim's claim cause.
     * @param findings one row per evaluated rule (PASS/FAIL), to audit in {@code rule_result}.
     */
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
            // An exclusion with no claim causes configured excludes nothing: there's no rule to
            // evaluate or audit (the referente can leave the list empty from the UI).
            if (rule.excludedClaimCauseIds() == null || rule.excludedClaimCauseIds().isEmpty()) {
                continue;
            }
            boolean causeExcluded = isExcludedBy(rule, claim.claimCauseId());
            // PASS = the coverage covers the claim cause (rule satisfied);
            // FAIL = it excludes it (the rule fires).
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
            log.info("[CoverageRuleEvaluator] Hecho generador '{}' (id={}) excluido por la cobertura {} — bloquea Fast Track",
                    claim.claimCause(), claim.claimCauseId(), claim.coverageId());
        }
        return new Result(excluded, findings);
    }

    /**
     * Whether the coverage excludes a claim cause, by id. Same check {@link #evaluate} runs against
     * the <b>declared</b> cause, exposed so the narrative-consistency pass can ask it about the
     * cause the account actually describes — which is the whole point: the model says which cause
     * the story is, the engine says whether that one is covered (CLAUDE.md #4).
     *
     * @param claimCauseId the cause to check; {@code null} (unmappable) is never excluded
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

    /** Readable reasons for the analyst, from the findings that failed. */
    public List<String> excludedReasons(Result result, ClaimReport claim) {
        return result.findings().stream()
                .filter(f -> !f.passed())
                .map(f -> "La cobertura no cubre el hecho generador declarado ("
                        + claim.claimCause() + ") — exclusión configurada por la aseguradora")
                .toList();
    }
}
