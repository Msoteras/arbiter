package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * A hard rule evaluated in code (not interpreted by the LLM), served to the classification engine.
 * The {@code id} is the {@code insurer_rule}'s and <b>has to travel</b>: it's what later goes into
 * {@code rule_result.rule_id}, and without it no audit is possible (SSN Disposition 2/2023). Today
 * the only type is {@code COVERAGE_EXCLUSION}; the engine matches the claim's claim cause against
 * {@code excludedClaimCauseIds} by id.
 */
public record EvaluableRuleDto(
        Long id,
        String ruleType,
        String effect,
        boolean blocksFastTrack,
        List<Long> excludedClaimCauseIds
) {}
