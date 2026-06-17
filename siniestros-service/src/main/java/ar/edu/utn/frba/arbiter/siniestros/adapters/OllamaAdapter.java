package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.exceptions.ClasificacionInvalidaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class OllamaAdapter implements SiniestroClassifier {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapter.class);

    private static final Map<String, Object> SCHEMA_SALIDA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "clasificacion", Map.of("type", "string",
                            "enum", List.of("POTENCIAL_RIESGO", "SIN_RIESGO", "FAST_TRACK", "FALTA_DOCUMENTACION", "REQUIERE_ANALISIS_MANUAL")),
                    "factores", Map.of("type", "array",
                            "items", Map.of("type", "string")),
                    "confianza", Map.of("type", "number", "minimum", 0, "maximum", 1)
            ),
            "required", List.of("clasificacion", "factores", "confianza")
    );

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;
    private final String plantillaPrompt;
    private final int numCtx;

    public OllamaAdapter(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            @Value("classpath:prompts/clasificacion-v1.md") Resource promptResource,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx
    ) throws IOException {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.plantillaPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        this.numCtx = numCtx;
    }

    @Override
    public ClasificacionResponse clasificar(ClasificacionRequest request) {
        String prompt = construirPrompt(request);
        int tokenesEstimados = prompt.length() / 4;

        log.info("[Ollama] Iniciando clasificación — modelo={} ramo='{}' hechoGenerador='{}' tokens_estimados=~{} num_ctx={}",
                properties.model(), request.ramo(), request.hechoGenerador(), tokenesEstimados, numCtx);

        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", prompt)),
                false,
                SCHEMA_SALIDA,
                Map.of("num_ctx", numCtx)
        );

        long inicio = System.currentTimeMillis();
        log.info("[Ollama] Enviando request a {} ...", properties.baseUrl() + "/api/chat");

        byte[] respuestaBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(chatRequest)
                .retrieve()
                .body(byte[].class);

        long latenciaMs = System.currentTimeMillis() - inicio;
        log.info("[Ollama] Stream recibido en {} ms, leyendo contenido...", latenciaMs);

        String contenidoFinal = leerRespuestaStreaming(new ByteArrayInputStream(respuestaBytes));

        if (contenidoFinal.isEmpty()) {
            log.error("[Ollama] Respuesta vacía tras {} ms", latenciaMs);
            throw new ClasificacionInvalidaException("Ollama devolvió una respuesta vacía");
        }

        log.debug("[Ollama] Contenido raw: {}", contenidoFinal);

        ClasificacionResponse resultado = parsearRespuesta(contenidoFinal);
        log.info("[Ollama] Clasificación: {} | confianza: {} | factores: {}",
                resultado.clasificacion(), resultado.confianza(), resultado.factores().size());

        return resultado;
    }

    private String leerRespuestaStreaming(InputStream inputStream) {
        StringBuilder contenidoCompleto = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                if (!linea.isEmpty()) {
                    try {
                        // Cada línea es un JSON válido: { "message": { "content": "..." }, "done": false }
                        Map<String, Object> chunk = objectMapper.readValue(linea, Map.class);
                        Map<String, Object> message = (Map<String, Object>) chunk.get("message");
                        if (message != null) {
                            String content = (String) message.get("content");
                            if (content != null) {
                                contenidoCompleto.append(content);
                            }
                        }
                    } catch (IOException e) {
                        log.warn("[Ollama] No se pudo parsear chunk (seguir leyendo): {}", linea.substring(0, Math.min(100, linea.length())));
                    }
                }
            }
        } catch (IOException e) {
            log.error("[Ollama] Error leyendo stream de respuesta", e);
            throw new ClasificacionInvalidaException("Error al leer respuesta de Ollama: " + e.getMessage(), e);
        }

        String resultado = contenidoCompleto.toString().trim();
        if (resultado.isEmpty()) {
            log.warn("[Ollama] Stream procesado pero contenido vacío");
        }
        return resultado;
    }

    private String construirPrompt(ClasificacionRequest request) {
        String adjuntosTexto = request.adjuntosOCR() == null || request.adjuntosOCR().isEmpty()
                ? "Sin documentos adjuntos"
                : String.join("\n\n---\n\n", request.adjuntosOCR());

        String historial = request.historialAsegurado() == null
                ? "Sin historial previo disponible"
                : request.historialAsegurado();

        String reglas = request.reglasAseguradora() == null
                ? "Sin reglas adicionales configuradas"
                : request.reglasAseguradora();

        return plantillaPrompt
                .replace("{{ramo}}", request.ramo())
                .replace("{{producto}}", request.producto())
                .replace("{{hechoGenerador}}", request.hechoGenerador())
                .replace("{{bienAsegurado}}", request.bienAsegurado())
                .replace("{{descripcionLibre}}", request.descripcionLibre())
                .replace("{{adjuntosOCR}}", adjuntosTexto)
                .replace("{{reglasAseguradora}}", reglas)
                .replace("{{historialAsegurado}}", historial);
    }

    private ClasificacionResponse parsearRespuesta(String contenidoJson) {
        try {
            SalidaModelo salida = objectMapper.readValue(contenidoJson, SalidaModelo.class);
            Clasificacion clasificacion = Clasificacion.valueOf(salida.clasificacion());
            return new ClasificacionResponse(clasificacion, salida.factores(), salida.confianza());
        } catch (IllegalArgumentException e) {
            throw new ClasificacionInvalidaException(
                    "El modelo devolvió un valor de clasificación inválido: " + contenidoJson, e);
        } catch (Exception e) {
            throw new ClasificacionInvalidaException(
                    "No se pudo parsear la respuesta del modelo: " + contenidoJson, e);
        }
    }

    // --- Records internos para el protocolo de la API de Ollama ---

    private record ChatMessage(String role, String content) {}

    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, Map<String, Object> options) {}

    private record ChatResponseMessage(String role, String content) {}

    private record ChatResponse(ChatResponseMessage message, boolean done) {}

    private record SalidaModelo(String clasificacion, List<String> factores, double confianza) {}
}
