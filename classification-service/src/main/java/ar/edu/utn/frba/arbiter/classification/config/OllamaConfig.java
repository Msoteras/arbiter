package ar.edu.utn.frba.arbiter.classification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, OllamaProperties.class, GeminiProperties.class})
public class OllamaConfig {

    /**
     * No read timeout on purpose: a legitimate CPU inference can take close to an hour, and a dead
     * runner answers 500 (retried) rather than hanging. Runaway generation is capped by
     * {@code num_predict} instead.
     */
    @Bean
    @ConditionalOnProperty(name = "arbiter.llm.provider", havingValue = "ollama", matchIfMissing = true)
    public RestClient ollamaRestClient(OllamaProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Spring Boot 4 only auto-configures a Jackson 3 mapper, so the Jackson 2 one is declared here.
     * {@link JavaTimeModule} is required: this bean also writes {@code policy_snapshot.insurer_db_payload},
     * which has {@code LocalDate}s, and without it that audit payload silently ends up null.
     * ISO-8601 strings keep the stored JSON human-readable.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
