package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Default {@link LlmClient}: Ollama's {@code /api/chat}, streamed as NDJSON. */
@Component
@ConditionalOnProperty(name = "arbiter.llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;
    private final int numCtx;
    private final int numPredict;

    public OllamaClient(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            ObjectMapper objectMapper,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx,
            // Hard cap on generated tokens: a repetition loop would otherwise run to num_ctx,
            // which on CPU means hours blocking the classification thread.
            @Value("${arbiter.ollama.num-predict:4096}") int numPredict
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.numCtx = numCtx;
        this.numPredict = numPredict;
    }

    @Override
    public int contextWindow() {
        return numCtx;
    }

    @Override
    public String model() {
        return properties.model();
    }

    private static final long PROGRESS_LOG_INTERVAL_MS = 5000;
    private static final int PROGRESS_LOG_TAIL_CHARS = 200;

    /**
     * Streams so progress can be logged periodically: a repetition loop then shows up as repeated
     * text in the logs instead of looking like a slow but healthy response.
     *
     * <p>{@code think=false} alone is not enough: {@code qwen3-vl:8b-thinking} ignores it and reasons
     * anyway. What actually matters is using the {@code -instruct} variant.
     */
    @Override
    public String chat(String prompt, List<String> images, Map<String, Object> format, boolean think) {
        ChatRequest request = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", prompt, images == null ? List.of() : images)),
                true,
                format,
                think,
                Map.of("num_ctx", numCtx, "num_predict", numPredict)
        );

        long start = System.currentTimeMillis();
        log.info("[Ollama] POST {}/api/chat — model={} images={}",
                properties.baseUrl(), properties.model(), images == null ? 0 : images.size());

        String content = ollamaRestClient.post()
                .uri("/api/chat")
                .body(request)
                .exchange((clientRequest, clientResponse) ->
                        readStreamingResponse(clientResponse.getBody(), start));

        log.info("[Ollama] Response received in {} ms ({} chars)",
                System.currentTimeMillis() - start, content.length());
        return content;
    }

    private String readStreamingResponse(InputStream inputStream, long start) {
        StringBuilder fullContent = new StringBuilder();
        // Logged only: tells "still thinking" apart from "hung".
        int thinkingChars = 0;
        long lastLogAt = start;
        int lastLoggedProgress = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> chunk = objectMapper.readValue(line, Map.class);
                    Map<String, Object> message = (Map<String, Object>) chunk.get("message");
                    if (message != null) {
                        String piece = (String) message.get("content");
                        if (piece != null) {
                            fullContent.append(piece);
                        }
                        String thinking = (String) message.get("thinking");
                        if (thinking != null) {
                            thinkingChars += thinking.length();
                        }
                    }
                } catch (IOException e) {
                    log.warn("[Ollama] Could not parse chunk (continuing): {}",
                            line.substring(0, Math.min(100, line.length())));
                    continue;
                }

                long now = System.currentTimeMillis();
                int progress = fullContent.length() + thinkingChars;
                if (now - lastLogAt >= PROGRESS_LOG_INTERVAL_MS && progress > lastLoggedProgress) {
                    log.info("[Ollama] ...still generating — {} chars of answer ({} of thinking), {} ms elapsed, tail: \"...{}\"",
                            fullContent.length(), thinkingChars, now - start, tail(fullContent));
                    lastLogAt = now;
                    lastLoggedProgress = progress;
                }
            }
        } catch (IOException e) {
            log.error("[Ollama] Error reading response stream", e);
            throw new InvalidClassificationException("Error reading Ollama response: " + e.getMessage(), e);
        }

        if (fullContent.isEmpty() && thinkingChars > 0) {
            log.warn("[Ollama] The model spent the whole response thinking ({} chars) without answering — "
                    + "it likely ran out of num_predict ({}) while reasoning", thinkingChars, numPredict);
        }
        return fullContent.toString().trim();
    }

    private String tail(StringBuilder content) {
        return content.substring(Math.max(0, content.length() - PROGRESS_LOG_TAIL_CHARS))
                .replace("\n", "\\n");
    }

    private record ChatMessage(String role, String content, List<String> images) {}

    /** A thinking model can spend the whole {@code num_predict} budget reasoning and return empty content. */
    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, boolean think, Map<String, Object> options) {}
}
