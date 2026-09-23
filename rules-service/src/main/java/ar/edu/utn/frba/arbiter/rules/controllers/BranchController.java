package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.BranchRequest;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.services.BranchCatalogService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRUD of the branch catalog. {@code branch} is global, not per-tenant: creating or deleting a
 * branch affects every insurer.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Ramos", description = "Catálogo de ramos (branch): lista y ABM")
public class BranchController {

    private final BranchCatalogService service;

    // Reading is also for the analyst: the reports screen filters by branch.
    @GetMapping("/branches")
    @PreAuthorize("hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')")
    @Operation(summary = "Ramos disponibles",
            description = "Catálogo de ramos (id + nombre) para la pantalla de reglas y el filtro de reportes.")
    public List<CatalogOption> list() {
        return service.list();
    }

    @PostMapping("/branches")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Alta de ramo", description = "Crea un ramo en el catálogo global. Nombre único.")
    public CatalogOption create(@RequestBody @Valid BranchRequest request) {
        return service.create(request.name());
    }

    @PutMapping("/branches/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Renombrar ramo", description = "Cambia el nombre de un ramo del catálogo. Nombre único.")
    public CatalogOption rename(@PathVariable Long id, @RequestBody @Valid BranchRequest request) {
        return service.rename(id, request.name());
    }

    @DeleteMapping("/branches/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Baja de ramo",
            description = "Elimina un ramo del catálogo. Falla con 409 si todavía tiene hechos generadores, "
                    + "coberturas o reglas asociadas.")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
