package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.DocumentRequirementDto;
import ar.edu.utn.frba.arbiter.rules.services.DocumentRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agenda documental del referente, por ramo (fan-out interno a los hechos generadores del ramo —
 * ver {@link DocumentRequirementService}).
 */
@RestController
@RequestMapping("/api/v1/rules/document-requirements")
@RequiredArgsConstructor
@Tag(name = "Agenda documental", description = "Documentos requeridos por ramo")
public class DocumentRequirementController {

    private final DocumentRequirementService documentRequirementService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Agenda documental de un ramo")
    public List<String> get(@RequestParam Long branchId) {
        return documentRequirementService.get(branchId);
    }

    @PutMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar agenda documental de un ramo",
            description = "Reemplaza los documentos requeridos para todos los hechos generadores del ramo. "
                    + "Devuelve las filas que quedaron persistidas (una por documento × hecho generador).")
    public List<DocumentRequirementDto> upsert(@RequestParam Long branchId, @RequestBody List<String> documentTypes) {
        return documentRequirementService.upsert(branchId, documentTypes);
    }
}
