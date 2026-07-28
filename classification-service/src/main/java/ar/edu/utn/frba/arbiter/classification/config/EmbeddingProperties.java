package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arbiter.embedding")
public record EmbeddingProperties(
        boolean enabled,
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
