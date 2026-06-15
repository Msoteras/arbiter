package ar.edu.utn.frba.arbiter.siniestros.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arbiter.ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        String promptVersion
) {}
