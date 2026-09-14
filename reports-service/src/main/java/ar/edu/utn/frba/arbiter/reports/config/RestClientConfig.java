package ar.edu.utn.frba.arbiter.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * El builder que necesita {@code RulesServiceClient} para hablar con rules-service. Declarado a
 * mano, igual que en cases-service y classification-service: en esta versión de Spring Boot el
 * starter web no lo auto-configura, y sin este bean el contexto no levanta.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
