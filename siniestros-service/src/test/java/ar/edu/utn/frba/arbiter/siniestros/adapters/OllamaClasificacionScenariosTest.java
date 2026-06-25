package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.siniestros.support.AbstractPersistenceIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integracion")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest
class OllamaClasificacionScenariosTest extends AbstractPersistenceIT {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private SiniestroClassifier classifier;

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

    // Nota: FAST_TRACK ya no es una salida posible del LLM — se decide antes,
    // de forma determinística, con FastTrackValidator (ver ClasificacionOrchestrator).
    // Los escenarios fast-track quedaron afuera de este test porque ya no aplican a este nivel.
    @ParameterizedTest(name = "Scenario: {0}")
    @ValueSource(strings = {
            "escenario-posible-riesgo",
            "escenario-posible-riesgo-2",
            "escenario-posible-riesgo-3",
            "escenario-falta-documentacion-1",
            "escenario-falta-documentacion-2",
            "escenario-analisis-manual-1",
            "escenario-analisis-manual-2"
    })
    void classifyScenario(String scenario) throws IOException {
        JsonNode fixture = loadFixture(scenario + ".json");
        String name = fixture.get("name").asText();
        Clasificacion expected = Clasificacion.valueOf(fixture.get("expectedClassification").asText());

        ClassificationRequest request = buildRequest(fixture.get("request"));
        ClassificationResponse response = classifier.classify(request);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║ SCENARIO: %-50s║%n", name);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║ Expected:     %-46s║%n", expected);
        System.out.printf( "║ Obtained:     %-46s║%n", response.classification());
        System.out.printf( "║ Confidence:   %-46s║%n", String.format("%.2f", response.confidence()));
        System.out.printf( "║ Match:        %-46s║%n", response.classification() == expected ? "YES ✓" : "NO ✗");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Factors:");
        for (String factor : response.factors()) {
            System.out.printf("║   • %-55s║%n", truncate(factor, 55));
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        assertThat(response.classification())
                .as("Classification for: %s", name)
                .isNotNull();
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);

        assertThat(response.classification())
                .as("The model should classify '%s' as %s", name, expected)
                .isEqualTo(expected);
    }

    private JsonNode loadFixture(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + filename)) {
            assertThat(is).as("Fixture not found: " + filename).isNotNull();
            return mapper.readTree(is);
        }
    }

    private ClassificationRequest buildRequest(JsonNode req) {
        List<String> attachments = new ArrayList<>();
        if (req.has("attachmentsOcr")) {
            req.get("attachmentsOcr").forEach(n -> attachments.add(n.asText()));
        }

        return ClassificationRequest.builder()
                .branch(req.get("branch").asText())
                .product(req.get("product").asText())
                .claimCause(req.get("claimCause").asText())
                .insuredItem(req.get("insuredItem").asText())
                .description(req.get("description").asText())
                .attachmentsOcr(attachments)
                .insurerRules(optionalText(req, "insurerRules"))
                .insuredHistory(optionalText(req, "insuredHistory"))
                .build();
    }

    private String optionalText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
