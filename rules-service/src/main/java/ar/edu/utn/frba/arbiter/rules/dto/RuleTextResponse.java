package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/** Save confirmation: the {@code insurer_rule} row and its content as persisted. */
public record RuleTextResponse(Long id, Long branchId, String ruleType, List<String> items) {}
