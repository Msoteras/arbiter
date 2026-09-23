package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.services.ClaimCauseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/claim-causes")
@RequiredArgsConstructor
@Tag(name = "Claim causes", description = "Hechos generadores por ramo")
public class ClaimCauseController {

    private final ClaimCauseService claimCauseService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hechos generadores de un ramo",
            description = "Nombres de los hechos generadores del ramo dado, para el selector del alta de "
                    + "denuncia. Ramo desconocido ⇒ lista vacía. Con policyNumber, recorta los que la "
                    + "cobertura de esa póliza excluye.")
    public List<String> byBranch(
            @RequestParam String branch,
            @RequestParam(required = false) String policyNumber
    ) {
        return claimCauseService.namesByBranch(branch, policyNumber);
    }

    @GetMapping("/all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Todos los hechos generadores (nombres distintos)",
            description = "Para el filtro 'Tipo de siniestro' de la bandeja, que es global (todos los "
                    + "ramos). Nombres distintos, ordenados.")
    public List<String> all() {
        return claimCauseService.allDistinctNames();
    }
}
