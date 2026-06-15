package ar.edu.utn.frba.arbiter.siniestros.controllers;

import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.services.ClasificacionOrquestador;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clasificaciones")
@RequiredArgsConstructor
@Tag(name = "Clasificaciones", description = "Clasificación de siniestros con LLM")
public class ClasificacionController {

    private final ClasificacionOrquestador orquestador;

    @PostMapping
    @Operation(
            summary = "Clasificar un siniestro",
            description = """
                    Recibe los datos de una denuncia, consulta el historial del asegurado
                    y las reglas de la aseguradora, construye un prompt estructurado
                    y lo envía al LLM (Ollama + Qwen3-VL) para clasificar el siniestro.

                    Los campos `polizaNumero` y `aseguradoDni` se usan para consultar
                    los mocks (en dev/test) o la API real de la aseguradora (en prod).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Clasificación exitosa",
                            content = @Content(schema = @Schema(implementation = ClasificacionResponse.class))),
                    @ApiResponse(responseCode = "422", description = "El LLM devolvió una respuesta inválida"),
                    @ApiResponse(responseCode = "400", description = "Datos de la denuncia inválidos")
            }
    )
    public ResponseEntity<ClasificacionResponse> clasificar(@Valid @RequestBody DenunciaSiniestro denuncia) {
        ClasificacionResponse respuesta = orquestador.clasificar(denuncia);
        return ResponseEntity.ok(respuesta);
    }
}
