package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No API key on purpose: the SDK authenticates with Application Default Credentials.
 *
 * @param location        also where abuse monitoring keeps its copy: a data-residency decision, not just latency
 * @param maxOutputTokens caps a runaway generation, which is billed (same role as Ollama's {@code num_predict})
 */
@ConfigurationProperties(prefix = "arbiter.gemini")
public record GeminiProperties(
        String project,
        String location,
        String model,
        Integer maxOutputTokens,
        Integer contextWindow
) {}
