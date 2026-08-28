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
     * Credentials come from ADC — {@code gcloud auth application-default login} on a developer
     * machine, a service account file in Docker. Deliberately not an API key: a key would be a
     * secret this service has to hold, and it would reach the Gemini Developer API instead of
     * Vertex, which is a different data-handling policy (its free tier may use prompts to improve
     * Google's products; Vertex does not).
     */
    // `genAiClient` y no `geminiClient`: ese nombre ya lo ocupa el @Component GeminiClient, que es
    // nuestro adapter. Dos beans con el mismo nombre no arrancan.
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
