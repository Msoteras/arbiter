package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CoverageDetailResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageOption;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageSummary;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.services.CoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Coberturas de la aseguradora por ramo: catálogo de solo-lectura para selectores de otros módulos,
 * más el CRUD que usa la solapa Coberturas del backoffice de reglas del referente. cases-service es
 * el dueño de la tabla Coverage, por eso se sirve desde acá.
 */
@RestController
@RequestMapping("/api/v1/coverages")
@RequiredArgsConstructor
@Tag(name = "Coverages", description = "Coberturas por ramo: catálogo y configuración del referente")
public class CoverageController {

    private final CoverageService coverageService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Coberturas de un ramo",
            description = "Lista las coberturas de la aseguradora del referente para el ramo dado.")
    public List<CoverageOption> listByBranch(@RequestParam Long branchId) {
        return coverageService.listByBranch(branchId);
    }

    @GetMapping("/detailed")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Coberturas de un ramo, con detalle",
            description = "Igual que el listado simple, pero con los campos que edita la solapa Coberturas.")
    public List<CoverageDetailResponse> listDetailedByBranch(@RequestParam Long branchId) {
        return coverageService.listDetailedByBranch(branchId);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Cantidad de coberturas por ramo",
            description = "Un conteo por cada ramo con al menos una cobertura, para la lista de ramos "
                    + "del panel de reglas — evita mostrar \"0 coberturas\" en un ramo que el referente "
                    + "todavía no clickeó.")
    public List<CoverageSummary> summary() {
        return coverageService.summary();
    }

    @PostMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Alta de cobertura",
            description = "Crea una cobertura nueva dentro del ramo dado.")
    public CoverageDetailResponse create(@RequestParam Long branchId, @RequestBody @Valid CoverageUpsertRequest request) {
        return coverageService.create(branchId, request);
    }

    @PutMapping("/{coverageId}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Editar cobertura",
            description = "Actualiza nombre, cláusula, franquicia, plazo de denuncia, tope anual y exclusiones.")
    public CoverageDetailResponse update(@PathVariable Long coverageId, @RequestBody @Valid CoverageUpsertRequest request) {
        return coverageService.update(coverageId, request);
    }

    @DeleteMapping("/{coverageId}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Baja de cobertura")
    public void delete(@PathVariable Long coverageId) {
        coverageService.delete(coverageId);
    }
}
