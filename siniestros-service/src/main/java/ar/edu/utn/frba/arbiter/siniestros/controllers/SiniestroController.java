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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/siniestros")
@RequiredArgsConstructor
@Tag(name = "Siniestros", description = "Gestión del ciclo de vida de siniestros")
public class SiniestroController {

    private static final Logger log = LoggerFactory.getLogger(SiniestroController.class);

    private final ClasificacionJob clasificacionJob;
    private final ResultadosClasificacionService resultadosService;
    private final AtomicLong siniestroCounter = new AtomicLong(0);
    private final Set<String> procesados = ConcurrentHashMap.newKeySet();

    @PostMapping
    @Operation(
            summary = "Crear denuncia de siniestro",
            description = "Recibe los datos de una denuncia y dispara la clasificación en background. Devuelve 202 Accepted inmediatamente."
    )
    @ApiResponse(responseCode = "202", description = "Denuncia aceptada, clasificación en progreso")
    @ApiResponse(responseCode = "400", description = "Datos de denuncia inválidos o duplicados")
    public ResponseEntity<Map<String, Object>> crearDenuncia(@Valid @RequestBody DenunciaSiniestro denuncia) {
        String denunciaKey = generarClave(denuncia);

        log.info("[SiniestroController] Nueva denuncia recibida — póliza='{}' dni='{}' ramo='{}' hechoGenerador='{}'",
                denuncia.polizaNumero(), denuncia.aseguradoDni(), denuncia.ramo(), denuncia.hechoGenerador());

        // Validar que no sea un duplicado
        if (procesados.contains(denunciaKey)) {
            log.warn("[SiniestroController] RECHAZADO: Denuncia duplicada — póliza='{}' dni='{}'",
                    denuncia.polizaNumero(), denuncia.aseguradoDni());
            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error", "DUPLICADO",
                            "mensaje", "Esta denuncia ya fue procesada previamente.",
                            "póliza", denuncia.polizaNumero(),
                            "dni", denuncia.aseguradoDni()
                    ));
        }

        Long siniestroId = siniestroCounter.incrementAndGet();
        procesados.add(denunciaKey);

        log.info("[SiniestroController] Denuncia aceptada — siniestroId={} póliza='{}' dni='{}'",
                siniestroId, denuncia.polizaNumero(), denuncia.aseguradoDni());

        clasificacionJob.procesarClasificacion(siniestroId, denuncia);

        log.debug("[SiniestroController] Job async disparado para siniestroId={}", siniestroId);

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/siniestros/" + siniestroId + "/resultado"))
                .body(Map.of(
                        "siniestroId", siniestroId,
                        "estado", "PENDIENTE_CLASIFICACION",
                        "mensaje", "Denuncia aceptada. Clasificación en progreso (~15-30 segundos).",
                        "consultarResultado", "/api/v1/siniestros/resultados"
                ));
    }

    private String generarClave(DenunciaSiniestro denuncia) {
        return denuncia.polizaNumero() + "|" + denuncia.aseguradoDni() + "|" +
               denuncia.ramo() + "|" + denuncia.hechoGenerador();
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
