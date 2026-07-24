package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the duplicate-image-detection feature (CLIP sidecar + pgvector).
 * Kept separate from {@link OllamaConfig} because embeddings no longer go
 * through Ollama — the CLIP service is a standalone dependency.
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, GoogleVisionProperties.class})
public class EmbeddingConfig {
}
