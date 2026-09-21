package ar.edu.utn.frba.arbiter.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * El builder que necesita {@code RulesServiceClient} para hablar con rules-service. Declarado a
 * mano, igual que en cases-service y classification-service: en esta versión de Spring Boot el
 * starter web no lo auto-configura, y sin este bean el contexto no levanta.
 */
@Configuration
public class RestClientConfig {

    /**
     * Cortos a propósito: rules-service corre en el mismo host y lo que se le pide es un GET a una
     * tabla de configuración. Si tarda más que esto está caído, y el tablero ya sabe seguir sin el
     * objetivo —lo dice {@code RulesServiceClient}— en vez de dejar al referente esperando.
     *
     * <p>Sin tope, un rules-service que acepta la conexión y no contesta colgaba el tablero entero
     * sin límite. Es la llamada que abre {@code ClaimMetricsService.generate}, así que nada de lo
     * demás empieza hasta que ésta vuelve.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
