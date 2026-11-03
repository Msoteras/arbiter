package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.EngineBoardResponse;
import ar.edu.utn.frba.arbiter.cases.services.EngineBoardService;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Engine board", description = "Lo que está clasificando el motor, en vivo")
public class EngineBoardController {

    private final EngineBoardService engineBoardService;

    @GetMapping("/engine")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Tablero del motor",
            description = """
                    Los expedientes de la aseguradora que están en cola o clasificándose, con el paso
                    en que está cada uno, y los que terminaron de clasificarse hoy. Los cambios
                    siguientes llegan por WebSocket en /topic/engine.<aseguradora>.
                    """)
    public EngineBoardResponse board() {
        return engineBoardService.board();
    }

    @PostMapping("/{caseId}/classification-steps")
    @PreAuthorize("authentication.authorities.isEmpty()")
    @Operation(summary = "Paso de una clasificación",
            description = """
                    Lo llama classification-service, solo con token de servicio, cada vez que la
                    clasificación de un expediente pasa a otro paso. Se guarda y se reenvía al
                    tablero del motor. No cambia el expediente.
                    """)
    public ResponseEntity<Void> step(@PathVariable Long caseId, @RequestBody @Valid ClassificationStep step) {
        engineBoardService.record(caseId, step);
        return ResponseEntity.noContent().build();
    }
}
