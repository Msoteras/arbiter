package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CoverageOption;
import ar.edu.utn.frba.arbiter.cases.services.CoverageCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Coberturas de la aseguradora por ramo, para poblar los selectores del backoffice de reglas del
 * referente. cases-service es el dueño de la tabla Coverage, por eso el catálogo se sirve desde acá.
 */
@RestController
@RequestMapping("/api/v1/coverages")
@RequiredArgsConstructor
@Tag(name = "Coverages", description = "Catálogo de coberturas por ramo para la configuración de reglas")
public class CoverageController {

    private final CoverageCatalogService coverageCatalogService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Coberturas de un ramo",
            description = "Lista las coberturas de la aseguradora del referente para el ramo dado.")
    public List<CoverageOption> listByBranch(@RequestParam Long branchId) {
        return coverageCatalogService.listByBranch(branchId);
    }
}
