package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings shared by every model provider; the prompt version belongs here because the same prompt
 * goes to whichever provider runs.
 *
 * @param provider      {@code ollama} (default) or {@code gemini}
 * @param promptVersion resolves {@code prompts/<version>.md}; persisted in {@code llm_analysis.prompt_version}
 */
@ConfigurationProperties(prefix = "arbiter.llm")
public record LlmProperties(
        String provider,
        String promptVersion
) {}
