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
    private final int numPredict;

    public OllamaClient(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            ObjectMapper objectMapper,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx,
            // Tope duro de tokens generados por respuesta. Sin esto, un loop de repetición del
            // modelo (fallo conocido, no específico de este proyecto) sigue generando hasta pegar
            // contra num_ctx entero — con qwen3-vl corriendo por CPU a ~3 tokens/seg eso son horas
            // bloqueando el thread de clasificación por una sola respuesta. 4096 es generoso para
            // lo que este flujo realmente pide (transcripción de un documento corto, o la
            // clasificación en sí — ninguna de las dos necesita miles de tokens de salida).
            @Value("${arbiter.ollama.num-predict:4096}") int numPredict
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.numCtx = numCtx;
        this.numPredict = numPredict;
    }

    public int numCtx() {
        return numCtx;
    }

    public String model() {
        return properties.model();
    }

    /** How often the in-progress content gets logged while a chat is still generating. */
    private static final long PROGRESS_LOG_INTERVAL_MS = 5000;
    /** How much of the tail of the accumulated content each progress log line shows. */
    private static final int PROGRESS_LOG_TAIL_CHARS = 200;

    /**
     * One logical chat call. {@code stream=true}: {@code retrieve().body(...)} blocks until the
     * whole response lands, so with {@code stream=false} nothing was visible from this side while
     * Ollama was generating — a hung or looping response (repetition is a known LLM failure mode)
     * looked identical in the logs to a normal one that just hadn't finished yet. Streaming lets
     * {@link #readStreamingResponse} log a snippet of what's actually coming out periodically,
     * so a loop shows up as literal repeated text in the logs instead of only as a low
     * tokens/second number in Ollama's own logs. The accumulated result is the same either way —
     * callers don't see a difference beyond the logging.
     *
     * @param images base64-encoded images for the vision model, or empty for text-only.
     * @param format optional JSON schema to force structured output, or null for free text.
     * @param think  whether to let the model reason before answering. <b>Ojo: no alcanza por sí
     *               solo.</b> Se midió contra Ollama 0.30.8 que {@code qwen3-vl:8b-thinking}
     *               ignora tanto {@code think:false} como la directiva {@code /no_think} de Qwen y
     *               razona igual. Lo que de verdad decide es <b>qué modelo se usa</b>: la variante
     *               {@code -instruct} no tiene fase de razonamiento. Esto viaja igual porque es la
     *               instrucción correcta a la API y deja la intención explícita.
     */
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
        // Solo para el log: los tokens de razonamiento no son la respuesta, pero saber que el
        // modelo está pensando (y no colgado, ni respondiendo) es exactamente el dato que faltaba
        // cuando una corrida devolvió 0 chars después de 27 minutos.
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
                    // Each line is a valid JSON: { "message": { "content": "..." }, "done": false }
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

    // --- Internal records for the Ollama API protocol ---

    private record ChatMessage(String role, String content, List<String> images) {}

    /**
     * @param think pide al modelo que no razone antes de responder. Por qué importa: con
     *              {@code num_predict} acotando cuánto puede generar, un modelo que piensa gasta
     *              el presupuesto entero razonando (en {@code message.thinking}) y termina sin
     *              emitir un solo carácter de {@code message.content} — se midió: dos documentos
     *              seguidos tardaron 27 min cada uno, exactamente lo que toma generar 4096 tokens,
     *              y devolvieron 0 chars. La solución real fue pasar al modelo {@code -instruct};
     *              ver el javadoc de {@link #chat}.
     */
    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, boolean think, Map<String, Object> options) {}
}
