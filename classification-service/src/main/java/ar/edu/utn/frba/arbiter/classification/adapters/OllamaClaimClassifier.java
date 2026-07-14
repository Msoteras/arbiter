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

@Service
@RequiredArgsConstructor
public class OllamaClaimClassifier implements ClaimClassifier {

    private static final Logger log = LoggerFactory.getLogger(OllamaClaimClassifier.class);

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

    private final OllamaClient client;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        String prompt = promptBuilder.buildFullPrompt(request);
        int estimatedTokens = prompt.length() / 4;

        log.info("[Ollama] Classifying — model={} branch='{}' claimCause='{}' estimated_tokens=~{} num_ctx={}",
                client.model(), request.branch(), request.claimCause(), estimatedTokens, client.numCtx());
        if (estimatedTokens > client.numCtx()) {
            log.warn("[Ollama] Prompt (~{} tokens) exceeds num_ctx ({}) — Ollama will silently drop the overflow",
                    estimatedTokens, client.numCtx());
        }
        log.debug("[Ollama] Full prompt sent:\n{}", prompt);

        String content = client.chat(prompt, List.of(), OUTPUT_SCHEMA);
        if (content.isEmpty()) {
            throw new InvalidClassificationException("Ollama returned an empty response");
        }
        log.debug("[Ollama] Raw content: {}", content);

        ClassificationResponse result = parseResponse(content);
        log.info("[Ollama] Classification: {} | confidence: {} | factors: {}",
                result.classification(), result.confidence(), result.factors().size());
        return result;
    }

    private ClassificationResponse parseResponse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            Classification classification = Classification.valueOf(output.classification());
            return new ClassificationResponse(classification, output.factors(), output.confidence(), false);
        } catch (IllegalArgumentException e) {
            throw new InvalidClassificationException(
                    "The model returned an invalid classification value: " + contentJson, e);
        } catch (Exception e) {
            throw new InvalidClassificationException(
                    "Could not parse model response: " + contentJson, e);
        }
    }

    private record ModelOutput(String classification, List<String> factors, double confidence) {}
}
