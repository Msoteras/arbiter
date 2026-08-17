package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * A hard rule evaluated by code (not interpreted by the LLM), served to the classification engine.
 * {@code id} is the {@code insurer_rule}'s and <b>has to travel</b>: it's what later goes into
 * {@code rule_result.rule_id} — without it there's no possible audit trail (Disposición SSN
 * 2/2023).
 *
 * <p>The type-specific fields are nullable because a single list carries different types:
 * {@code excludedClaimCauseIds} is only filled by {@code COVERAGE_EXCLUSION} and
 * {@code deadlineHours} only by {@code POLICE_DEADLINE}. The other hard rules travel with no
 * parameters on purpose — the row says the rule is active, and the coverage sets the threshold.
 */
public record EvaluableRuleDto(
        Long id,
        String ruleType,
        String effect,
        boolean blocksFastTrack,
        List<Long> excludedClaimCauseIds,
        Long deadlineHours
) {}
