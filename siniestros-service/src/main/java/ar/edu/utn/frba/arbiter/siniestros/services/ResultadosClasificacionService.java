package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ResultadosClasificacionService {

    private static final Logger log = LoggerFactory.getLogger(ResultadosClasificacionService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${arbiter.resultados.path}")
    private String resultadosPath;

    public void guardarResultado(
            Long siniestroId,
            String polizaNumero,
            String aseguradoDni,
            Clasificacion clasificacion,
            Double confianza,
            List<String> factores,
            Long latenciaMs
    ) {
        try {
            Path path = Paths.get(resultadosPath);

            // Crear archivo si no existe, con encabezado
            if (!Files.exists(path)) {
                crearArchivoConEncabezado(path);
            }

            // Agregar fila con resultado
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String factoresStr = String.join(" | ", factores);
            String fila = String.format(
                    "| %d | %s | %s | CLASIFICADO | %s | %.2f | %d ms | %s |\n",
                    siniestroId,
                    polizaNumero,
                    aseguradoDni,
                    clasificacion,
                    confianza,
                    latenciaMs,
                    timestamp
            );

            Files.write(path, fila.getBytes(), StandardOpenOption.APPEND);
            log.info("[ResultadosClasificacionService] Resultado guardado para siniestro {} en {}", siniestroId, resultadosPath);

        } catch (IOException e) {
            log.error("[ResultadosClasificacionService] Error al guardar resultado para siniestro {}", siniestroId, e);
        }
    }

    private void crearArchivoConEncabezado(Path path) throws IOException {
        String encabezado = """
                # Resultados de Clasificación de Siniestros

                Generado automáticamente por el módulo de análisis y clasificación.

                | ID | Póliza | DNI | Estado | Clasificación | Confianza | Latencia | Timestamp |
                |----|--------|-----|--------|---------------|-----------|----------|-----------|
                """;

        Files.createDirectories(path.getParent());
        Files.write(path, encabezado.getBytes());
    }

    public String obtenerContenido() {
        try {
            Path path = Paths.get(resultadosPath);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
            return "No hay resultados aún.";
        } catch (IOException e) {
            log.error("Error al leer archivo de resultados", e);
            return "Error al leer resultados.";
        }
    }
}
