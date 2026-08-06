package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
import ar.edu.utn.frba.arbiter.rules.services.FastTrackRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Backoffice del referente: carga y edición de los umbrales Fast Track de su aseguradora,
 * por (rama, cobertura), más el catálogo de ramos para poblar el selector. El schema del tenant
 * se resuelve del JWT, así que el referente solo ve/edita las reglas de su propia aseguradora.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Reglas Fast Track", description = "Configuración de umbrales Fast Track por ramo y cobertura")
public class FastTrackRuleController {

    private final FastTrackRuleService fastTrackRuleService;

    @GetMapping("/branches")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Ramos disponibles",
            description = "Lista los ramos (arbiter_common) para poblar el selector del backoffice de reglas.")
    public List<CatalogOption> branches() {
        return fastTrackRuleService.listBranches();
    }

    @GetMapping("/fast-track")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Umbrales Fast Track de una cobertura",
            description = "Devuelve los umbrales configurados para (ramo, cobertura). Si no hay regla, "
                    + "devuelve una configuración vacía (todos los campos en null).")
    public FastTrackConfigDto get(@RequestParam Long branchId, @RequestParam Long coverageId) {
        return fastTrackRuleService.get(branchId, coverageId);
    }

    @GetMapping("/internal/fast-track")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Umbrales Fast Track por cobertura",
            description = "Lectura system-to-system para classification-service (sin rol REFERENTE): la "
                    + "usa el motor de clasificación con un token de servicio que lleva el tenant. Keyea "
                    + "por cobertura, que es lo que el claim tiene a mano.")
    public FastTrackConfigDto internalFastTrack(@RequestParam Long coverageId) {
        return fastTrackRuleService.getByCoverage(coverageId);
    }

    @PutMapping("/fast-track")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Guardar umbrales Fast Track de una cobertura",
            description = "Crea o actualiza la regla FAST_TRACK de (ramo, cobertura). Cada cambio deja "
                    + "un snapshot en el historial (auditoría append-only). Sin redeploy.")
    public void upsert(
            @RequestParam Long branchId,
            @RequestParam Long coverageId,
            @RequestBody @Valid FastTrackConfigDto config,
            Authentication authentication) {
        fastTrackRuleService.upsert(branchId, coverageId, config, authentication.getName());
    }
}
