package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.services.ExpertFirmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catálogo de peritos de la aseguradora, administrado por el referente desde la pantalla de reglas.
 * Es lo que ve el analista en el selector al derivar: si acá no hay nadie para el ramo, no hay a
 * quién derivar por más que la regla de monto lo habilite.
 *
 * <p>Solo REFERENTE: el analista lee el catálogo filtrado por su expediente, en
 * {@code GET /cases/{id}/expert-assessment/options}, no acá.
 */
@RestController
@RequestMapping("/api/v1/expert-firms")
@RequiredArgsConstructor
@Tag(name = "Expert firms", description = "Catálogo de peritos externos de la aseguradora")
public class ExpertFirmController {

    private final ExpertFirmService expertFirmService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Listar el catálogo completo",
            description = "Activos e inactivos: el referente administra el catálogo entero.")
    public ResponseEntity<List<ExpertFirmResponse>> list() {
        return ResponseEntity.ok(expertFirmService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Agregar un perito",
            description = "`branchId` null = generalista (cubre todos los ramos). Un ramo que no "
                    + "existe da 422.")
    public ResponseEntity<ExpertFirmResponse> create(@RequestBody @Valid ExpertFirmRequest request) {
        return ResponseEntity.ok(expertFirmService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Editar un perito",
            description = "Incluye `active`: desactivarlo lo saca del selector del analista sin "
                    + "tocar las derivaciones que ya recibió.")
    public ResponseEntity<ExpertFirmResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid ExpertFirmRequest request
    ) {
        return ResponseEntity.ok(expertFirmService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Borrar un perito que nunca se usó",
            description = "409 si ya tiene peritajes: en ese caso se desactiva, no se borra, para "
                    + "no perder el rastro de a quién se le derivó.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        expertFirmService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
