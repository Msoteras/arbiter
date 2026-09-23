package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Disabled by default: the only integration that sends claim images outside our infrastructure,
 * so it is an explicit opt-in per deployment and requires the insured's consent.
 */
@ConfigurationProperties(prefix = "arbiter.google-vision")
public record GoogleVisionProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        int maxResults
) {
    public GoogleVisionProperties {
        if (baseUrl == null) baseUrl = "https://vision.googleapis.com";
        if (maxResults <= 0) maxResults = 10;
    }
}
