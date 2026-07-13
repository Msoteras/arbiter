package ar.edu.utn.frba.arbiter.classification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaConfig {

    @Bean
    public RestClient ollamaRestClient(OllamaProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Jackson 2 mapper the Ollama adapters use to parse the NDJSON stream and the model's
     * structured output. Spring Boot 4 defaults HTTP to Jackson 3 (tools.jackson) and no longer
     * auto-configures a com.fasterxml.jackson.databind.ObjectMapper bean, so we provide one.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
