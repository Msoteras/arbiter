package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real Ollama instance (OPTIONAL).
 * Requires Ollama running with qwen3-vl at the configured URL.
 * Automatically skipped if Ollama is not available.
 *
 * Run: mvn -pl classification-service test -Dgroups=ollama -Dtest=ClassificationOllamaIntegrationTest
 * Or simply: mvn -pl classification-service test (skipped if Ollama is not available)
 */
@Tag("ollama")
@SpringBootTest
@ActiveProfiles("test")
class ClassificationOllamaIntegrationTest extends AbstractPersistenceIT {

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private ClassificationOrchestrator orchestrator;

    @BeforeEach
    void checkOllamaAvailable() {
        String url = ollamaProperties.baseUrl() + "/api/tags";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.connect();
            assumeTrue(conn.getResponseCode() == 200, "Ollama not available at " + url);
        } catch (IOException e) {
            assumeTrue(false, "Ollama not available — " + e.getMessage());
        }
    }

    @Test
    void recidivistClaim_validateOllamaResponse() {
        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Premium")
                .claimCause("Robo en vía pública")
                .insuredItem("iPhone 16 Pro Max 256GB - IMEI 353000000000099")
                .insuredId("30.555.777")
                .policyNumber("POL-CEL-2025-099")
                .description(
                        "Me robaron el celular el martes a la noche, estaba en la calle creo que por " +
                        "Palermo o tal vez Belgrano, no me acuerdo bien la dirección exacta. Eran como " +
                        "las 11 o 12 de la noche. Vino un tipo y me lo sacó de la mano. No vi bien " +
                        "porque estaba oscuro. Hice la report al día siguiente."
                )
                .eventDate(LocalDateTime.of(2026, 6, 10, 23, 0))
                .eventLocation("Palermo, CABA (ubicación imprecisa)")
                .attachmentsOcr(List.of(
                        "DENUNCIA POLICIAL Nro 2026/78901 - Comisaría 14va CABA\n" +
                        "Fecha: 12/06/2026 09:15 hs\n" +
                        "Reportnte: Marcelo Gómez DNI 30.555.777\n" +
                        "Hecho: Robo de teléfono celular"
                ))
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        System.out.println("\n=== Real Ollama Response ===");
        System.out.println("Classification: " + response.classification());
        System.out.println("Confidence: " + response.confidence());
        System.out.println("Factors: " + response.factors());

        assertThat(response.classification()).isIn(
                Classification.FALTA_DOCUMENTACION,
                Classification.LLM_RECOMIENDA_APROBAR,
                Classification.LLM_NO_RECOMIENDA_APROBAR,
                Classification.LLM_SOLICITA_REVISION_MANUAL
        );
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);
    }
}
