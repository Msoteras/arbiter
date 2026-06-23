package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.exceptions.ClasificacionInvalidaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class OllamaAdapter implements SiniestroClassifier, DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapter.class);

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "classification", Map.of("type", "string",
                            "enum", List.of("POTENCIAL_RIESGO", "SIN_RIESGO", "FAST_TRACK", "FALTA_DOCUMENTACION", "REQUIERE_ANALISIS_MANUAL")),
                    "factors", Map.of("type", "array",
                            "items", Map.of("type", "string")),
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
            ),
            "required", List.of("classification", "factors", "confidence")
    );

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;
    private final String documentExtractionPrompt;
    private final int numCtx;

    public OllamaAdapter(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            @Value("classpath:prompts/clasificacion-v1.md") Resource promptResource,
            @Value("classpath:prompts/extraccion-documento-v1.md") Resource documentExtractionPromptResource,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx
    ) throws IOException {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.numCtx = numCtx;
    }

    @Override
    public ClasificacionResponse classify(ClasificacionRequest request) {
        String prompt = buildPrompt(request);
        int estimatedTokens = prompt.length() / 4;

        log.info("[Ollama] Starting classification — model={} branch='{}' claimCause='{}' estimated_tokens=~{} num_ctx={}",
                properties.model(), request.branch(), request.claimCause(), estimatedTokens, numCtx);

        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", prompt, List.of())),
                false,
                OUTPUT_SCHEMA,
                Map.of("num_ctx", numCtx)
        );

        long start = System.currentTimeMillis();
        log.info("[Ollama] Sending request to {} ...", properties.baseUrl() + "/api/chat");

        byte[] responseBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(chatRequest)
                .retrieve()
                .body(byte[].class);

        long latencyMs = System.currentTimeMillis() - start;
        log.info("[Ollama] Stream received in {} ms, reading content...", latencyMs);

        String finalContent = readStreamingResponse(new ByteArrayInputStream(responseBytes));

        if (finalContent.isEmpty()) {
            log.error("[Ollama] Empty response after {} ms", latencyMs);
            throw new ClasificacionInvalidaException("Ollama returned an empty response");
        }

        log.debug("[Ollama] Raw content: {}", finalContent);

        ClasificacionResponse result = parseResponse(finalContent);
        log.info("[Ollama] Classification: {} | confidence: {} | factors: {}",
                result.classification(), result.confidence(), result.factors().size());

        return result;
    }

    @Override
    public String extractText(byte[] content, String contentType) {
        String base64 = Base64.getEncoder().encodeToString(content);

        log.info("[Ollama] Starting document analysis — model={} contentType={} sizeBytes={}",
                properties.model(), contentType, content.length);

        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", documentExtractionPrompt, List.of(base64))),
                false,
                null,
                Map.of("num_ctx", numCtx)
        );

        long start = System.currentTimeMillis();

        byte[] responseBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(chatRequest)
                .retrieve()
                .body(byte[].class);

        long latencyMs = System.currentTimeMillis() - start;
        String extractedText = readStreamingResponse(new ByteArrayInputStream(responseBytes));

        if (extractedText.isEmpty()) {
            log.warn("[Ollama] Document analysis returned empty content after {} ms", latencyMs);
            return "No se pudo extraer contenido del documento adjunto.";
        }

        log.info("[Ollama] Document analysis done in {} ms — extracted {} chars", latencyMs, extractedText.length());
        return extractedText;
    }

    private String readStreamingResponse(InputStream inputStream) {
        StringBuilder fullContent = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    try {
                        // Each line is a valid JSON: { "message": { "content": "..." }, "done": false }
                        Map<String, Object> chunk = objectMapper.readValue(line, Map.class);
                        Map<String, Object> message = (Map<String, Object>) chunk.get("message");
                        if (message != null) {
                            String content = (String) message.get("content");
                            if (content != null) {
                                fullContent.append(content);
                            }
                        }
                    } catch (IOException e) {
                        log.warn("[Ollama] Could not parse chunk (continuing): {}", line.substring(0, Math.min(100, line.length())));
                    }
                }
            }
        } catch (IOException e) {
            log.error("[Ollama] Error reading response stream", e);
            throw new ClasificacionInvalidaException("Error reading Ollama response: " + e.getMessage(), e);
        }

        String result = fullContent.toString().trim();
        if (result.isEmpty()) {
            log.warn("[Ollama] Stream processed but content is empty");
        }
        return result;
    }

    private String buildPrompt(ClasificacionRequest request) {
        String attachmentsText = request.attachmentsOcr() == null || request.attachmentsOcr().isEmpty()
                ? "Sin documentos adjuntos"
                : String.join("\n\n---\n\n", request.attachmentsOcr());

        String history = request.insuredHistory() == null
                ? "Sin historial previo disponible"
                : request.insuredHistory();

        String rules = request.insurerRules() == null
                ? "Sin reglas adicionales configuradas"
                : request.insurerRules();

        return promptTemplate
                .replace("{{branch}}", request.branch())
                .replace("{{product}}", request.product())
                .replace("{{claimCause}}", request.claimCause())
                .replace("{{insuredItem}}", request.insuredItem())
                .replace("{{description}}", request.description())
                .replace("{{attachmentsOcr}}", attachmentsText)
                .replace("{{insurerRules}}", rules)
                .replace("{{insuredHistory}}", history);
    }

    private ClasificacionResponse parseResponse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            Clasificacion classification = Clasificacion.valueOf(output.classification());
            return new ClasificacionResponse(classification, output.factors(), output.confidence());
        } catch (IllegalArgumentException e) {
            throw new ClasificacionInvalidaException(
                    "The model returned an invalid classification value: " + contentJson, e);
        } catch (Exception e) {
            throw new ClasificacionInvalidaException(
                    "Could not parse model response: " + contentJson, e);
        }
    }

    // --- Internal records for Ollama API protocol ---

    private record ChatMessage(String role, String content, List<String> images) {}

    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, Map<String, Object> options) {}

    private record ChatResponseMessage(String role, String content) {}

    private record ChatResponse(ChatResponseMessage message, boolean done) {}

    private record ModelOutput(String classification, List<String> factors, double confidence) {}
}
