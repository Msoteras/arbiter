package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Las reglas evaluables de una cobertura, juntas — como las consume el motor de clasificación en un
 * solo round-trip, igual que {@link RuleTextsDto}. Sin configuración devuelve una lista vacía, nunca
 * 404: el motor compone esto sobre su baseline y una clasificación no puede caerse porque falte
 * configuración.
 */
public record EvaluableRulesDto(List<EvaluableRuleDto> rules) {

    public static EvaluableRulesDto empty() {
        return new EvaluableRulesDto(List.of());
    }
}
