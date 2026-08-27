package ar.edu.utn.frba.arbiter.classification.adapters;

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

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ClaimClassifierImpl implements ClaimClassifier {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassifierImpl.class);

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "classification", Map.of("type", "string",
                            "enum", List.of("LLM_RECOMIENDA_APROBAR",
                                    "LLM_NO_RECOMIENDA_APROBAR", "LLM_SOLICITA_REVISION_MANUAL")),
                    "factors", Map.of("type", "array",
                            "items", Map.of("type", "string")),
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
            ),
            "required", List.of("classification", "factors", "confidence")
    );

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

        // Sin thinking, igual que la extracción: el schema ya obliga al modelo a explicitar sus
        // `factores`, que ES el razonamiento que le pedimos —y el que después ve el analista—, así
        // que una fase de razonamiento previa e invisible duplicaría el trabajo. Corriendo por CPU
        // eso son decenas de minutos por caso. Si algún día se corre con GPU y se quiere evaluar si
        // pensar mejora la recomendación, es cambiar este false y medir.
        String content = client.chat(prompt, List.of(), OUTPUT_SCHEMA, false);
        if (content.isEmpty()) {
            throw new InvalidClassificationException("Ollama returned an empty response");
        }
        log.debug("[LLM] Raw content: {}", content);

        ClassificationResponse result = parseResponse(content);
        log.info("[LLM] Classification: {} | confidence: {} | factors: {}",
                result.classification(), result.confidence(), result.factors().size());
        return result;
    }

    private ClassificationResponse parseResponse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            Classification classification = Classification.valueOf(output.classification());
            return new ClassificationResponse(
                    classification, plainText(output.factors()), output.confidence(), false);
        } catch (IllegalArgumentException e) {
            throw new InvalidClassificationException(
                    "The model returned an invalid classification value: " + contentJson, e);
        } catch (Exception e) {
            throw new InvalidClassificationException(
                    "Could not parse model response: " + contentJson, e);
        }
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

    private record ModelOutput(String classification, List<String> factors, double confidence) {}
}
