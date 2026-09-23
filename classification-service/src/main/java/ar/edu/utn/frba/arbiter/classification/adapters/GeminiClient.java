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
 * Gemini through Vertex. Opt-in ({@code arbiter.llm.provider=gemini}) because the prompt leaves our
 * infrastructure. {@code think} is ignored: Gemini manages its own reasoning budget.
 */
@Component
@ConditionalOnProperty(name = "arbiter.llm.provider", havingValue = "gemini")
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** Callers pass base64 without a MIME type. */
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
            // responseJsonSchema takes plain JSON Schema; responseSchema's OpenAPI subset can't
            // express `["string","null"]`.
            config.responseMimeType("application/json").responseJsonSchema(format);
        }

        long start = System.currentTimeMillis();
        log.info("[Gemini] generateContent — model={} images={}",
                properties.model(), images == null ? 0 : images.size());

        GenerateContentResponse response =
                client.models.generateContent(properties.model(), Content.fromParts(parts.toArray(Part[]::new)), config.build());

        String content = response.text();
        // Billed per token.
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
