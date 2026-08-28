package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * A coverage's evaluable rules, together — the way the classification engine consumes them in a
 * single round-trip, same as {@link RuleTextsDto}. With no configuration it returns an empty list,
 * never a 404: the engine composes this over its baseline and a classification can't fall over
 * missing config.
 */
public record EvaluableRulesDto(List<EvaluableRuleDto> rules) {

    public static EvaluableRulesDto empty() {
        return new EvaluableRulesDto(List.of());
    }
}
