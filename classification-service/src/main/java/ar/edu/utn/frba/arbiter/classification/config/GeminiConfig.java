package ar.edu.utn.frba.arbiter.classification.config;

import com.google.genai.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Vertex-backed Gemini client, built only when it is the configured provider — otherwise a
 * team running Ollama would need GCP credentials just to boot the service.
 */
@Configuration
@ConditionalOnProperty(name = "arbiter.llm.provider", havingValue = "gemini")
public class GeminiConfig {

    private static final Logger log = LoggerFactory.getLogger(GeminiConfig.class);

    /**
     * Credentials come from ADC, deliberately not an API key: a key would target the Gemini
     * Developer API, whose data-handling policy differs from Vertex's.
     * Named {@code genAiClient} because {@code geminiClient} is already the adapter bean.
     */
    @Bean
    public Client genAiClient(GeminiProperties properties) {
        log.info("[Gemini] Vertex backend — project={} location={} model={}",
                properties.project(), properties.location(), properties.model());
        return Client.builder()
                .vertexAI(true)
                .project(properties.project())
                .location(properties.location())
                .build();
    }
}
