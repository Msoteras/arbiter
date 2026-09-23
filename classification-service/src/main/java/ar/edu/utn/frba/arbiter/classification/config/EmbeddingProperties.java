package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No on/off switch on purpose: with embeddings off nothing would match internally, and the web
 * escalation (triggered on "no internal match") would send every claim image to a third party.
 */
@ConfigurationProperties(prefix = "arbiter.embedding")
public record EmbeddingProperties(
        String serviceUrl,
        String model,
        double similarityThreshold,
        int maxResults
) {
    public EmbeddingProperties {
        if (serviceUrl == null) serviceUrl = "http://localhost:8000";
        if (model == null) model = "clip-vit-b-32-openai";
        if (similarityThreshold <= 0) similarityThreshold = 0.90;
        if (maxResults <= 0) maxResults = 5;
    }
}
