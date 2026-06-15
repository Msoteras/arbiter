package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
@SpringBootTest
class OllamaClasificacionEscenariosTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private SiniestroClassifier classifier;

    @BeforeEach
    void verificarOllamaDisponible() {
        String url = ollamaProperties.baseUrl() + "/api/tags";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.connect();
            assumeTrue(conn.getResponseCode() == 200, "Ollama no disponible en " + url);
        } catch (IOException e) {
            assumeTrue(false, "Ollama no disponible — " + e.getMessage());
        }
    }

    @ParameterizedTest(name = "Escenario: {0}")
    @ValueSource(strings = {
            "escenario-potencial-riesgo",
            "escenario-sin-riesgo",
            "escenario-fast-track"
    })
    void clasificarEscenario(String escenario) throws IOException {
        JsonNode fixture = cargarFixture(escenario + ".json");
        String nombre = fixture.get("nombre").asText();
        Clasificacion esperada = Clasificacion.valueOf(fixture.get("clasificacionEsperada").asText());

        ClasificacionRequest request = armarRequest(fixture.get("request"));
        ClasificacionResponse respuesta = classifier.clasificar(request);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║ ESCENARIO: %-49s║%n", nombre);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║ Esperada:     %-46s║%n", esperada);
        System.out.printf( "║ Obtenida:     %-46s║%n", respuesta.clasificacion());
        System.out.printf( "║ Confianza:    %-46s║%n", String.format("%.2f", respuesta.confianza()));
        System.out.printf( "║ Coincide:     %-46s║%n", respuesta.clasificacion() == esperada ? "SÍ ✓" : "NO ✗");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Factores:");
        for (String factor : respuesta.factores()) {
            System.out.printf("║   • %-55s║%n", truncar(factor, 55));
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        assertThat(respuesta.clasificacion())
                .as("Clasificación para: %s", nombre)
                .isNotNull();
        assertThat(respuesta.factores()).isNotEmpty();
        assertThat(respuesta.confianza()).isBetween(0.0, 1.0);

        assertThat(respuesta.clasificacion())
                .as("El modelo debería clasificar '%s' como %s", nombre, esperada)
                .isEqualTo(esperada);
    }

    private JsonNode cargarFixture(String archivo) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + archivo)) {
            assertThat(is).as("No se encontró el fixture: " + archivo).isNotNull();
            return mapper.readTree(is);
        }
    }

    private ClasificacionRequest armarRequest(JsonNode req) {
        List<String> adjuntos = new ArrayList<>();
        if (req.has("adjuntosOCR")) {
            req.get("adjuntosOCR").forEach(n -> adjuntos.add(n.asText()));
        }

        return ClasificacionRequest.builder()
                .ramo(req.get("ramo").asText())
                .producto(req.get("producto").asText())
                .hechoGenerador(req.get("hechoGenerador").asText())
                .bienAsegurado(req.get("bienAsegurado").asText())
                .descripcionLibre(req.get("descripcionLibre").asText())
                .adjuntosOCR(adjuntos)
                .reglasAseguradora(textoOpcional(req, "reglasAseguradora"))
                .historialAsegurado(textoOpcional(req, "historialAsegurado"))
                .build();
    }

    private String textoOpcional(JsonNode node, String campo) {
        return node.has(campo) ? node.get(campo).asText() : null;
    }

    private String truncar(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max - 1) + "…";
    }
}
