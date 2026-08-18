package ar.edu.utn.frba.arbiter.classification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaConfig {

    /**
     * <b>Sin read timeout a propósito.</b> Corriendo por CPU una sola inferencia legítima ya tardó
     * ~58 minutos, y la curva de tokens/seg se degrada con el contexto, así que cualquier número
     * que se elija corre el riesgo de cortar una respuesta que iba a llegar bien. Además, el modo
     * de falla que realmente se vio (el runner de Ollama muerto por falta de memoria) no deja la
     * conexión colgada: Ollama responde <b>500</b>, que {@code @Retryable} en
     * {@code ClaimClassificationService} ya maneja. El timeout habría sido para un cuelgue sin
     * respuesta, que no es lo que pasa acá.
     *
     * <p>Contra el otro riesgo —un loop de repetición del modelo generando para siempre— la
     * defensa es {@code num_predict} ({@link OllamaClient}), que lo corta por cantidad de tokens
     * en vez de por reloj.
     *
     * <p>El connect timeout sí queda: si Ollama no está levantado, eso falla en segundos y no hay
     * razón para esperar.
     */
    @Bean
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
     * Jackson 2 mapper the Ollama adapters use to parse the NDJSON stream and the model's
     * structured output. Spring Boot 4 defaults HTTP to Jackson 3 (tools.jackson) and no longer
     * auto-configures a com.fasterxml.jackson.databind.ObjectMapper bean, so we provide one.
     *
     * <p>{@link JavaTimeModule} registered explicitly: a bare mapper can't serialize
     * {@code java.time} types, and this bean is also what writes the audit payload of
     * {@code policy_snapshot.insurer_db_payload} — whose {@code InsuredPolicy} carries
     * {@code LocalDate} vigencia dates. Without it that serialization failed and the faithful
     * record of what the insurer's DB answered was stored as null (D27 / SSN 2/2023), while the
     * classification itself proceeded and looked fine. ISO-8601 strings rather than epoch numbers
     * ({@code WRITE_DATES_AS_TIMESTAMPS} off) so the stored JSON stays readable by a human
     * auditing a case years later.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
