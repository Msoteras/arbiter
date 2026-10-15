package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * No API key on purpose: the SDK authenticates with Application Default Credentials.
 *
 * @param location        also where abuse monitoring keeps its copy: a data-residency decision, not just latency
 * @param maxOutputTokens caps a runaway generation, which is billed (same role as Ollama's {@code num_predict})
 * @param timeout         caps a whole call, the SDK's own retries included: a hung call holds one of
 *                        the few classification threads
 */
@ConfigurationProperties(prefix = "arbiter.gemini")
public record GeminiProperties(
        String project,
        String location,
        String model,
        Integer maxOutputTokens,
        Integer contextWindow,
        Duration timeout
) {}
