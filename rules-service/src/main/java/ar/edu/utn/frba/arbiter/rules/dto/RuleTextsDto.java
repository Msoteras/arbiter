package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * The branch's two free-text lists, together — the way the classification engine consumes them:
 * both go into the same prompt, so asking for them in one request instead of two saves a round-trip
 * per classification.
 */
public record RuleTextsDto(List<String> exclusions, List<String> businessRules) {

    public static RuleTextsDto empty() {
        return new RuleTextsDto(List.of(), List.of());
    }
}
