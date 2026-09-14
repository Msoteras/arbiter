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
 * El objetivo de resolución que fija el referente: en cuántos días se propone la compañía cerrar
 * un siniestro.
 *
 * <p>No es el plazo legal del art. 56 — ese es por expediente y no lo fija nadie — sino una meta de
 * gestión. Lo único que lo lee es el tablero de métricas.
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
     * Lectura system-to-system para reports-service, que lo necesita para cada tablero y no tiene
     * rol de referente: el token es el del usuario que está mirando el tablero, que puede ser un
     * analista. Mismo patrón que {@code /internal/policy-standing} para cases-service.
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
