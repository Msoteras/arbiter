package ar.edu.utn.frba.arbiter.classification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, GoogleVisionProperties.class})
public class EmbeddingConfig {
}
