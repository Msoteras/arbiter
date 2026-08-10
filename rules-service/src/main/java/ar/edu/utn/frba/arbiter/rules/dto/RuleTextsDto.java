package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Las dos listas de texto libre del ramo, juntas — es como las consume el motor de clasificación:
 * ambas van al mismo prompt, así que pedirlas en un request en vez de dos evita un round-trip por
 * clasificación.
 */
public record RuleTextsDto(List<String> exclusions, List<String> businessRules) {

    public static RuleTextsDto empty() {
        return new RuleTextsDto(List.of(), List.of());
    }
}
