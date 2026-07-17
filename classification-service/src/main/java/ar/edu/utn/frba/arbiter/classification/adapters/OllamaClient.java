package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Low-level Ollama transport: owns the {@code /api/chat} protocol (request shape, num_ctx
 * option, NDJSON stream accumulation). Adapters build prompts and parse results on top of
 * this; they don't touch HTTP. See {@link OllamaClaimClassifier}, {@link OllamaDocumentAnalyzer}.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;
    private final int numCtx;

    public OllamaClient(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            ObjectMapper objectMapper,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.numCtx = numCtx;
    }

    public int numCtx() {
        return numCtx;
    }

    public String model() {
        return properties.model();
    }

    /**
     * One logical chat call. {@code stream=false}, but Ollama still answers with NDJSON, so
     * the message content is accumulated across lines. Returns the raw concatenated content —
     * callers decide what an empty result means (error vs. fallback text).
     *
     * @param images base64-encoded images for the vision model, or empty for text-only.
     * @param format optional JSON schema to force structured output, or null for free text.
     */
    public String chat(String prompt, List<String> images, Map<String, Object> format) {
        ChatRequest request = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", prompt, images == null ? List.of() : images)),
                false,
                format,
                Map.of("num_ctx", numCtx)
        );

        long start = System.currentTimeMillis();
        log.info("[Ollama] POST {}/api/chat — model={} images={}",
                properties.baseUrl(), properties.model(), images == null ? 0 : images.size());

        byte[] responseBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(byte[].class);

        String content = readStreamingResponse(new ByteArrayInputStream(responseBytes));
        log.info("[Ollama] Response received in {} ms ({} chars)",
                System.currentTimeMillis() - start, content.length());
        return content;
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
                        log.warn("[Ollama] Could not parse chunk (continuing): {}",
                                line.substring(0, Math.min(100, line.length())));
                    }
                }
            }
        } catch (IOException e) {
            log.error("[Ollama] Error reading response stream", e);
            throw new InvalidClassificationException("Error reading Ollama response: " + e.getMessage(), e);
        }

        return fullContent.toString().trim();
    }

    // --- Internal records for the Ollama API protocol ---

    private record ChatMessage(String role, String content, List<String> images) {}

    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, Map<String, Object> options) {}
}
