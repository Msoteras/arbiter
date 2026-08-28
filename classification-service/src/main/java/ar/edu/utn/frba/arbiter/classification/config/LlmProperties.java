package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What is common to every model provider.
 *
 * <p>{@code promptVersion} lives here and not under a provider: the prompt is ours, the same file
 * goes to whoever serves the tokens, and it is what gets persisted in
 * {@code llm_analysis.prompt_version} for the SSN 2/2023 audit. Leaving it under
 * {@code arbiter.ollama} would have meant a claim classified by Gemini auditing its prompt version
 * from a section named after the provider that did not run it.
 *
 * @param provider which {@code LlmClient} gets wired: {@code ollama} (default) or {@code gemini}
 * @param promptVersion resolves {@code prompts/<version>.md} and is stored with every classification
 */
@ConfigurationProperties(prefix = "arbiter.llm")
public record LlmProperties(
        String provider,
        String promptVersion
) {}
