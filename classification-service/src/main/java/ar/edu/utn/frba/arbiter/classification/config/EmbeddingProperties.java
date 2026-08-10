package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * There is no on/off switch here on purpose: reuse detection is part of what the analysis IS,
 * not an optional extra. A switch also made a second flag lie — with embeddings off nothing ever
 * matched internally, and the web escalation (which triggers precisely on "no internal match")
 * would have sent every claim image to a third party.
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
