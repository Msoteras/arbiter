package ar.edu.utn.frba.arbiter.expedientes.controllers;

import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteRequest;
import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteResponse;
import ar.edu.utn.frba.arbiter.expedientes.services.ExpedienteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/expedientes")
@RequiredArgsConstructor
@Tag(name = "Expedientes", description = "Expediente lifecycle management")
public class ExpedienteController {

    private final ExpedienteService expedienteService;

    @PostMapping
    @Operation(summary = "Create expediente",
            description = "Registrates an expediente using the same request structure as the claim flow and triggers a mocked analysis with siniestros-service.")
    public ResponseEntity<ExpedienteResponse> createExpediente(
            @RequestBody @Valid ExpedienteRequest request
    ) {
        ExpedienteResponse response = expedienteService.createExpediente(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{expedienteId}")
    @Operation(summary = "Get expediente by id",
            description = "Returns the stored expediente and its mocked analysis result.")
    public ResponseEntity<ExpedienteResponse> getExpediente(@PathVariable Long expedienteId) {
        ExpedienteResponse response = expedienteService.getExpediente(expedienteId);
        return ResponseEntity.ok(response);
    }
}
