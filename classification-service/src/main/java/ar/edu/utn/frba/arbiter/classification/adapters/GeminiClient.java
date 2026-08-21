package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.GeminiProperties;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gemini served through Vertex, behind the same {@link LlmClient} as Ollama.
 *
 * <p>Why it exists: classifying a claim on a laptop CPU took between twenty minutes and an hour,
 * which makes the flow untestable end to end. This trades that for seconds, at the cost of the
 * prompt leaving our infrastructure — so it is opt-in per environment
 * ({@code arbiter.llm.provider=gemini}) and the default stays Ollama.
 *
 * <p>Two Ollama-specific things have no counterpart here and are dropped on purpose:
 * <ul>
 *   <li>{@code num_ctx} — the window is a property of the model, not something we set. What is
 *       kept is {@link #contextWindow()}, so the caller can still warn before overflowing it.</li>
 *   <li>{@code think} — Gemini decides its own reasoning budget, and the parameter meant
 *       something specific to Qwen's thinking variant. Ignored rather than faked.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "arbiter.llm.provider", havingValue = "gemini")
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** What images arrive as. The adapters hand over base64 without saying what it decodes to. */
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/png";

    private final Client client;
    private final GeminiProperties properties;

    public GeminiClient(Client client, GeminiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String chat(String prompt, List<String> images, Map<String, Object> format, boolean think) {
        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText(prompt));
        if (images != null) {
            images.stream()
                    .map(base64 -> Part.fromBytes(Base64.getDecoder().decode(base64), DEFAULT_IMAGE_MIME_TYPE))
                    .forEach(parts::add);
        }

        GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                .maxOutputTokens(properties.maxOutputTokens());
        if (format != null) {
            // responseJsonSchema, not responseSchema: it takes plain JSON Schema, which is exactly
            // what the adapters already hand to Ollama. responseSchema would mean translating both
            // schemas into Vertex's OpenAPI subset, where `["string","null"]` has no equivalent —
            // and a silently degraded schema is a model that answers something that won't parse.
            config.responseMimeType("application/json").responseJsonSchema(format);
        }

        long start = System.currentTimeMillis();
        log.info("[Gemini] generateContent — model={} images={}",
                properties.model(), images == null ? 0 : images.size());

        GenerateContentResponse response =
                client.models.generateContent(properties.model(), Content.fromParts(parts.toArray(Part[]::new)), config.build());

        String content = response.text();
        // Billing is per token, so the count is worth logging: it is the only way to tell an
        // expensive prompt from a cheap one before the invoice says so.
        response.usageMetadata().ifPresent(usage -> log.info("[Gemini] Tokens — prompt={} candidates={} total={}",
                usage.promptTokenCount().orElse(0), usage.candidatesTokenCount().orElse(0),
                usage.totalTokenCount().orElse(0)));
        log.info("[Gemini] Response received in {} ms ({} chars)",
                System.currentTimeMillis() - start, content == null ? 0 : content.length());

        return content == null ? "" : content.trim();
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public int contextWindow() {
        return properties.contextWindow();
    }
}
