package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Whether the cause the documents narrate is the declared one. Off the LLM path nobody compared them:
 * a hurto declared as robo could Fast Track with a police report caratulado HURTO. <b>It warns, it
 * doesn't block</b> (team's call, 22/09/2026); with no document narrating a cause it writes no row.
 */
@Component
public class ClaimCauseConsistencyEvaluator {

    /** {@code rule_result.evaluated_value} is {@code VARCHAR(150)}. */
    private static final int EVALUATED_VALUE_MAX = 150;

    /** @param findings the audit row: PASS when every document that narrates a cause agrees */
    public record Result(List<String> reasons, List<RuleFinding> findings) {

        public static Result none() {
            return new Result(List.of(), List.of());
        }
    }

    /**
     * @param documents the extractions read so far, by attachment type: only the gate's on the Fast Track
     *                  path, all of them otherwise
     * @param catalog   the branch's claim causes, to say whether the narrated one is covered
     */
    public Result evaluate(ClaimReport claim, Map<String, DocumentExtraction> documents,
                           List<ClassificationRequest.ClaimCauseOption> catalog) {
        if (claim.claimCause() == null || documents.isEmpty()) {
            return Result.none();
        }

        // Document type -> the cause it narrates, for the ones that narrate any.
        Map<String, String> described = new LinkedHashMap<>();
        documents.forEach((type, extraction) -> {
            String cause = extraction.fields().describedClaimCause();
            if (cause != null) {
                described.put(type, cause);
            }
        });
        if (described.isEmpty()) {
            return Result.none();
        }

        Map<String, String> differing = new LinkedHashMap<>();
        described.forEach((type, cause) -> {
            if (!cause.equalsIgnoreCase(claim.claimCause())) {
                differing.put(type, cause);
            }
        });

        if (differing.isEmpty()) {
            return new Result(List.of(), List.of(finding(true, String.format("declared=%s described=%s documents=%s",
                    claim.claimCause(), claim.claimCause(), String.join(",", described.keySet())))));
        }

        List<String> narrated = differing.values().stream().distinct().toList();
        String evaluated = String.format("declared=%s described=%s documents=%s",
                claim.claimCause(), String.join(",", narrated), String.join(",", differing.keySet()));
        return new Result(List.of(reason(claim, narrated, catalog)), List.of(finding(false, evaluated)));
    }

    private String reason(ClaimReport claim, List<String> narrated,
                          List<ClassificationRequest.ClaimCauseOption> catalog) {
        List<String> uncovered = new ArrayList<>();
        for (String cause : narrated) {
            catalog.stream()
                    .filter(option -> option.name().equalsIgnoreCase(cause) && !option.covered())
                    .findFirst()
                    .ifPresent(option -> uncovered.add(option.name()));
        }
        String quoted = narrated.stream().map(cause -> "«" + cause + "»").collect(Collectors.joining(" y "));
        StringBuilder text = new StringBuilder("La documentación adjunta describe ")
                .append(quoted)
                .append(", no el hecho generador declarado («").append(claim.claimCause()).append("»)");
        if (!uncovered.isEmpty()) {
            text.append(narrated.size() == 1
                    ? ", que esta cobertura no cubre"
                    : ", y esta cobertura no cubre " + String.join(" ni ", uncovered));
        }
        return text.append(". Revisar el relato y el acta antes de resolver.").toString();
    }

    private static RuleFinding finding(boolean passed, String evaluatedValue) {
        String value = evaluatedValue.length() > EVALUATED_VALUE_MAX
                ? evaluatedValue.substring(0, EVALUATED_VALUE_MAX)
                : evaluatedValue;
        return new RuleFinding(null, RuleType.CLAIM_CAUSE_MATCH.name(), passed, value);
    }
}
