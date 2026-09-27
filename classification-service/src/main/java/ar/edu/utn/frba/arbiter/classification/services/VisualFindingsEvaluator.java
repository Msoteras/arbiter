package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns the vision model's signs of tampering into a warning the analyst can't miss.
 *
 * <p>The extraction already records them per document, but they were only visible opening each
 * document in the Documentation tab: a pasted amount on the invoice took the fast lane with no
 * warning at all. This surfaces them on every path, Fast Track included.
 *
 * <p><b>It warns, it doesn't block</b>, same as {@link ClaimCauseConsistencyEvaluator}: a sign is
 * the model's observation, not proof, and the analyst decides. And it only writes a row when there
 * are signs — a PASS would read as "the documents are authentic", which no absence of signs proves.
 */
@Component
public class VisualFindingsEvaluator {

    /** {@code rule_result.evaluated_value} is {@code VARCHAR(150)}. */
    private static final int EVALUATED_VALUE_MAX = 150;

    public record Result(List<String> reasons, List<RuleFinding> findings) {

        public static Result none() {
            return new Result(List.of(), List.of());
        }
    }

    /** @param documents the extractions read on this path, by attachment type */
    public Result evaluate(Map<String, DocumentExtraction> documents) {
        Map<String, List<String>> signsByType = new LinkedHashMap<>();
        documents.forEach((type, extraction) -> {
            if (!extraction.visualFindings().isEmpty()) {
                signsByType.put(type, extraction.visualFindings());
            }
        });
        if (signsByType.isEmpty()) {
            return Result.none();
        }

        int count = signsByType.values().stream().mapToInt(List::size).sum();
        String evaluated = truncate(String.format("documents=%s signs=%d",
                String.join(",", signsByType.keySet()), count));
        return new Result(
                List.of(reason(signsByType)),
                List.of(new RuleFinding(null, RuleType.VISUAL_TAMPERING.name(), false, evaluated)));
    }

    private String reason(Map<String, List<String>> signsByType) {
        String signs = signsByType.values().stream()
                .flatMap(List::stream)
                .map(sign -> "«" + sign + "»")
                .collect(Collectors.joining("; "));
        return "Señales de adulteración en la documentación: " + signs + ".";
    }

    private static String truncate(String value) {
        return value.length() > EVALUATED_VALUE_MAX ? value.substring(0, EVALUATED_VALUE_MAX) : value;
    }
}
