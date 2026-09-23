package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.services.PromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ClaimClassifierImpl implements ClaimClassifier {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassifierImpl.class);

    /**
     * Second lock besides the schema: {@code FAST_TRACK} must never come out of a model, even if a
     * provider ignores the schema or the attachments carry a prompt injection.
     */
    private static final Set<Classification> ALLOWED_FROM_MODEL = EnumSet.of(
            Classification.LLM_RECOMIENDA_APROBAR,
            Classification.LLM_NO_RECOMIENDA_APROBAR,
            Classification.LLM_SOLICITA_REVISION_MANUAL);

    private static final List<String> CLASSIFICATION_VALUES = List.of(
            "LLM_RECOMIENDA_APROBAR", "LLM_NO_RECOMIENDA_APROBAR", "LLM_SOLICITA_REVISION_MANUAL");

    private static final List<String> CONSISTENCY_VALUES =
            List.of(CauseConsistency.MATCHES.name(), CauseConsistency.AMBIGUOUS.name(),
                    CauseConsistency.CONTRADICTS.name());

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        String prompt = promptBuilder.buildFullPrompt(request);
        int estimatedTokens = prompt.length() / 4;

        log.info("[LLM] Classifying — model={} branch='{}' claimCause='{}' estimated_tokens=~{} num_ctx={}",
                client.model(), request.branch(), request.claimCause(), estimatedTokens, client.contextWindow());
        if (estimatedTokens > client.contextWindow()) {
            log.warn("[LLM] Prompt (~{} tokens) exceeds num_ctx ({}) — Ollama will silently drop the overflow",
                    estimatedTokens, client.contextWindow());
        }
        log.debug("[LLM] Full prompt sent:\n{}", prompt);

        // No thinking: the schema's `factors` already are the reasoning the analyst sees, and a hidden
        // reasoning phase costs tens of minutes per case on CPU.
        String content = client.chat(prompt, List.of(), outputSchema(request), false);
        if (content.isEmpty()) {
            throw new InvalidClassificationException("Ollama returned an empty response");
        }
        log.debug("[LLM] Raw content: {}", content);

        ClassificationResponse result = parseResponse(content);
        log.info("[LLM] Classification: {} | confidence: {} | factors: {} | causeConsistency: {}{}",
                result.classification(), result.confidence(), result.factors().size(),
                result.causeConsistency(),
                result.suggestedClaimCause() == null ? "" : " (relato sugiere '" + result.suggestedClaimCause() + "')");
        return result;
    }

    /**
     * Built per request: {@code suggestedClaimCause} is an {@code enum} of the branch's claim causes
     * (plus "" for not applicable), so the answer maps back to an id and can't invent a cause.
     * With no catalog it's a plain string, since an empty {@code enum} is an invalid schema.
     */
    private static Map<String, Object> outputSchema(ClassificationRequest request) {
        Map<String, Object> suggestedCause = new LinkedHashMap<>();
        suggestedCause.put("type", "string");
        List<ClassificationRequest.ClaimCauseOption> catalog = request.claimCauseCatalog();
        if (catalog != null && !catalog.isEmpty()) {
            List<String> names = new ArrayList<>();
            names.add("");
            catalog.stream().map(ClassificationRequest.ClaimCauseOption::name).forEach(names::add);
            suggestedCause.put("enum", names);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("classification", Map.of("type", "string", "enum", CLASSIFICATION_VALUES));
        properties.put("factors", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        properties.put("causeConsistency", Map.of("type", "string", "enum", CONSISTENCY_VALUES));
        properties.put("suggestedClaimCause", suggestedCause);
        properties.put("causeEvidence", Map.of("type", "string"));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("classification", "factors", "confidence",
                        "causeConsistency", "suggestedClaimCause", "causeEvidence"));
    }

    private ClassificationResponse parseResponse(String contentJson) {
        ModelOutput output;
        Classification classification;
        try {
            output = objectMapper.readValue(contentJson, ModelOutput.class);
            classification = Classification.valueOf(output.classification());
        } catch (IllegalArgumentException e) {
            throw new InvalidClassificationException(
                    "The model returned an invalid classification value: " + contentJson, e);
        } catch (Exception e) {
            throw new InvalidClassificationException(
                    "Could not parse model response: " + contentJson, e);
        }
        // Outside the try, or the generic catch would report it as a parse failure.
        if (!ALLOWED_FROM_MODEL.contains(classification)) {
            throw new InvalidClassificationException(
                    "The model returned a classification it is not allowed to decide: " + classification);
        }
        return ClassificationResponse.builder()
                .classification(classification)
                .factors(plainText(output.factors()))
                .confidence(output.confidence())
                .deterministicFastTrack(false)
                .causeConsistency(consistency(output.causeConsistency()))
                .suggestedClaimCause(blankToNull(output.suggestedClaimCause()))
                .causeEvidence(blankToNull(output.causeEvidence()))
                .build();
    }

    /** Only asterisks: factors carry real underscores (police_report, last_connection). */
    private List<String> plainText(List<String> factors) {
        if (factors == null) {
            return List.of();
        }
        return factors.stream()
                .filter(Objects::nonNull)
                .map(factor -> factor.replace("*", "").trim())
                .toList();
    }

    /**
     * A missing or unknown verdict degrades to {@code AMBIGUOUS} instead of throwing: consistency is
     * a support signal, and it must never sink a classification the analyst is waiting on. Only the
     * {@code classification} itself is worth failing over.
     */
    private CauseConsistency consistency(String value) {
        if (value == null || value.isBlank()) {
            return CauseConsistency.AMBIGUOUS;
        }
        try {
            return CauseConsistency.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            log.warn("[LLM] Unknown causeConsistency '{}' — treated as AMBIGUOUS", value);
            return CauseConsistency.AMBIGUOUS;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ModelOutput(String classification, List<String> factors, double confidence,
                               String causeConsistency, String suggestedClaimCause,
                               String causeEvidence) {}
}
