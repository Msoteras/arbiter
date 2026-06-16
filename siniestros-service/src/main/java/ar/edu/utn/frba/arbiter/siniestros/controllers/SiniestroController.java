package ar.edu.utn.frba.arbiter.siniestros.controllers;

import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.services.ClasificacionJob;
import ar.edu.utn.frba.arbiter.siniestros.services.ResultadosClasificacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/v1/siniestros")
@RequiredArgsConstructor
@Tag(name = "Siniestros", description = "Gestión del ciclo de vida de siniestros")
public class SiniestroController {

    private final ClasificacionJob clasificacionJob;
    private final ResultadosClasificacionService resultadosService;
    private final AtomicLong siniestroCounter = new AtomicLong(0);

    @PostMapping
    @Operation(
            summary = "Crear denuncia de siniestro",
            description = "Recibe los datos de una denuncia y dispara la clasificación en background. Devuelve 202 Accepted inmediatamente."
    )
    @ApiResponse(responseCode = "202", description = "Denuncia aceptada, clasificación en progreso")
    @ApiResponse(responseCode = "400", description = "Datos de denuncia inválidos")
    public ResponseEntity<Map<String, Object>> crearDenuncia(@Valid @RequestBody DenunciaSiniestro denuncia) {
        Long siniestroId = siniestroCounter.incrementAndGet();

        clasificacionJob.procesarClasificacion(siniestroId, denuncia);

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/siniestros/" + siniestroId + "/resultado"))
                .body(Map.of(
                        "siniestroId", siniestroId,
                        "estado", "PENDIENTE_CLASIFICACION",
                        "mensaje", "Denuncia aceptada. Clasificación en progreso.",
                        "consultarResultado", "/api/v1/siniestros/resultados"
                ));
    }

    @GetMapping("/resultados")
    @Operation(summary = "Obtener tabla de resultados de clasificaciones")
    public ResponseEntity<String> obtenerResultados() {
        String contenido = resultadosService.obtenerContenido();
        return ResponseEntity
                .ok()
                .contentType(MediaType.TEXT_MARKDOWN)
                .body(contenido);
    }
}
