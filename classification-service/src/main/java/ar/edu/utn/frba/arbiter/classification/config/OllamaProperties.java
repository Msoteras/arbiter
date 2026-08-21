package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ollama-specific settings. The prompt version is deliberately not here — it belongs to the
 * classification, not to whoever serves the tokens; see {@link LlmProperties}.
 */
@ConfigurationProperties(prefix = "arbiter.ollama")
public record OllamaProperties(
        String baseUrl,
        String model
) {}
