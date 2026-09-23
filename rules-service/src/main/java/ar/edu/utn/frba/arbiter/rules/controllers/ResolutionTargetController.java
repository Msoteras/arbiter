package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
import ar.edu.utn.frba.arbiter.rules.services.ResolutionTargetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The insurer's resolution target: in how many days it aims to close a claim. Not the art. 56 legal
 * deadline (that one is per case); a management goal read only by the metrics dashboard.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Objetivo de resolución",
        description = "Meta de días de la aseguradora para resolver un siniestro")
public class ResolutionTargetController {

    private final ResolutionTargetService service;

    @GetMapping("/resolution-target")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Objetivo de resolución de la aseguradora",
            description = "La aseguradora que nunca lo configuró vuelve con enabled=false y sin días.")
    public ResolutionTargetDto get() {
        return service.get();
    }

    @PutMapping("/resolution-target")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar el objetivo de resolución",
            description = "Con enabled=true, targetDays es obligatorio (1 a 365). Apagarlo no borra el "
                    + "número: si se vuelve a encender, vuelve el que había. Cada cambio deja snapshot "
                    + "en el historial — subir el objetivo saca de un día para el otro a la mitad de los "
                    + "expedientes de 'fuera de objetivo', y eso tiene que quedar registrado.")
    public ResolutionTargetDto upsert(
            @RequestBody @Valid ResolutionTargetDto target, Authentication authentication) {
        return service.upsert(target, authentication.getName());
    }

    /**
     * System-to-system read for reports-service: the token is the dashboard viewer's, who may be an
     * analyst rather than a referente.
     */
    @GetMapping("/internal/resolution-target")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Objetivo de resolución",
            description = "Lectura para reports-service, sin exigir rol REFERENTE. Sin configuración "
                    + "vuelve enabled=false y el tablero no muestra la comparación.")
    public ResolutionTargetDto internalGet() {
        return service.get();
    }
}
