package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini served through Vertex. There is no API key here on purpose: the SDK authenticates with
 * Application Default Credentials — {@code gcloud auth application-default login} on a developer
 * machine, a service account file in Docker — so the secret never becomes a property that could
 * end up committed in a yml.
 *
 * @param project     GCP project id that gets billed for the calls
 * @param location    region the request is served from, e.g. {@code us-central1}. Also where abuse
 *                    monitoring keeps its copy, so it is a data-residency decision, not just latency
 * @param model       model id, e.g. {@code gemini-3.5-flash}
 * @param maxOutputTokens ceiling on generated tokens. Same role as Ollama's {@code num_predict}:
 *                    the model is asked for a small JSON, and a runaway generation is billed
 */
@ConfigurationProperties(prefix = "arbiter.gemini")
public record GeminiProperties(
        String project,
        String location,
        String model,
        Integer maxOutputTokens,
        Integer contextWindow
) {}
