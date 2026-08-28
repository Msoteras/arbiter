package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Cloud Vision (Web Detection) settings. Disabled by default: this is the only
 * integration that sends claim images OUTSIDE our infrastructure, so it must be an explicit
 * opt-in per deployment and requires the insured's consent.
 *
 * <p>{@code apiKey} always comes from an environment variable — never commit it.
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
