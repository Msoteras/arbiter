package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.services.OnboardingCountsService;
import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
@Tag(name = "Puesta en marcha", description = "Datos de cases-service para la puesta en marcha")
public class OnboardingCountsController {

    private final OnboardingCountsService service;

    @GetMapping("/internal/counts")
    @PreAuthorize("authentication.authorities.isEmpty()")
    @Operation(summary = "[interno] Conteos para la puesta en marcha",
            description = "Lo llama rules-service, solo con token de servicio: los ramos con pólizas en "
                    + "la BD Aseguradora, los proveedores activos y los ramos con tope de liquidación.")
    public OnboardingCaseCounts counts() {
        return service.counts();
    }
}
