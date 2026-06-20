package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real Ollama instance.
 * Works locally (localhost:11434) or in Docker (ollama:11434).
 * URL is taken from OllamaProperties → OLLAMA_BASE_URL env var.
 * Automatically skipped if Ollama is not available.
 *
 * Local:  mvn -pl siniestros-service test -Dgroups=integracion
 * Docker: docker compose -f docker-compose.test.yml up --abort-on-container-exit
 */
@Tag("integracion")
@SpringBootTest
class OllamaAdapterIntegrationTest {

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private SiniestroClassifier siniestroClassifier;

    @BeforeEach
    void checkOllamaAvailable() {
        String healthUrl = ollamaProperties.baseUrl() + "/api/tags";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(healthUrl)
                    .toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.connect();
            assumeTrue(conn.getResponseCode() == 200,
                    "Ollama not available at " + healthUrl);
        } catch (IOException e) {
            assumeTrue(false, "Ollama not available at " + healthUrl + " — " + e.getMessage());
        }
    }

    @Test
    void classifyMobileTheft_shouldReturnValidClassification() {
        ClasificacionRequest request = ClasificacionRequest.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Samsung Galaxy A56 - IMEI 358000000000001")
                .description(
                        "El día 10 de junio de 2026, alrededor de las 20:30 hs, " +
                        "me encontraba caminando por Av. Corrientes al 3500 cuando dos personas " +
                        "en moto se me acercaron y me arrancaron el celular de la mano. " +
                        "Realicé la denuncia policial en la comisaría 5ta a las 22:00 hs."
                )
                .attachmentsOcr(List.of(
                        "DENUNCIA POLICIAL Nro 2026/45678 - Comisaría 5ta CABA\n" +
                        "Fecha: 10/06/2026 22:03 hs\n" +
                        "Denunciante: Juan Pérez DNI 38.000.001\n" +
                        "Hecho: Robo de teléfono celular en vía pública\n" +
                        "Lugar: Av. Corrientes 3500, CABA"
                ))
                .insurerRules(
                        "- El bien debe estar dentro del campo visual del asegurado al momento del robo.\n" +
                        "- Se requiere denuncia policial dentro de las 48 hs del hecho.\n" +
                        "- Franquicia: $50.000. Suma asegurada: $300.000."
                )
                .build();

        ClasificacionResponse response = siniestroClassifier.classify(request);

        System.out.println("=== MODEL RESPONSE ===");
        System.out.println("Classification: " + response.classification());
        System.out.println("Confidence:     " + response.confidence());
        System.out.println("Factors:");
        response.factors().forEach(f -> System.out.println("  - " + f));
        System.out.println("======================");

        assertThat(response.classification()).isNotNull();
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);
    }
}
