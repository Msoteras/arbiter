package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigResponse;
import ar.edu.utn.frba.arbiter.rules.services.ScoringConfigurationService;
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

/** Scoring de fraude (factores + bandas) del referente. Una sola config por aseguradora, no por ramo. */
@RestController
@RequestMapping("/api/v1/rules/scoring")
@RequiredArgsConstructor
@Tag(name = "Scoring de fraude", description = "Configuración de factores y bandas de riesgo de la aseguradora")
public class ScoringConfigurationController {

    private final ScoringConfigurationService scoringConfigurationService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Scoring de la aseguradora",
            description = "Si todavía no hay config, devuelve una vacía (enabled=false, sin factores ni bandas).")
    public ScoringConfigDto get() {
        return scoringConfigurationService.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar scoring de la aseguradora",
            description = "Reemplaza factores y bandas. Cada cambio deja un snapshot en el historial. "
                    + "Devuelve la config tal como quedó persistida, con el id de scoring_configuration.")
    public ScoringConfigResponse upsert(@RequestBody @Valid ScoringConfigDto config, Authentication authentication) {
        return scoringConfigurationService.upsert(config, authentication.getName());
    }
}
