package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.DocumentRequirementDto;
import ar.edu.utn.frba.arbiter.rules.services.DocumentRequirementService;
import ar.edu.utn.frba.arbiter.rules.services.InternalDocumentRequirementService;
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
 * The referente's document schedule, by branch (internal fan-out to the branch's claim causes —
 * see {@link DocumentRequirementService}).
 */
@RestController
@RequestMapping("/api/v1/rules/document-requirements")
@RequiredArgsConstructor
@Tag(name = "Agenda documental", description = "Documentos requeridos por ramo")
public class DocumentRequirementController {

    private final DocumentRequirementService documentRequirementService;
    private final InternalDocumentRequirementService internalDocumentRequirementService;

    @GetMapping("/internal")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Agenda documental por cobertura",
            description = "Lectura system-to-system para classification-service (sin rol REFERENTE): la usa "
                    + "el motor con un token de servicio que lleva el tenant. Keyea por cobertura —lo que el "
                    + "claim tiene a mano— y resuelve el ramo puertas adentro. Sin agenda devuelve lista "
                    + "vacía, nunca 404: el motor compone esto sobre su baseline.")
    public List<String> internalByCoverage(@RequestParam Long coverageId) {
        return internalDocumentRequirementService.getByCoverage(coverageId);
    }

    @GetMapping("/for-branch")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Agenda documental por nombre de ramo",
            description = "Lectura para el asegurado (arma el uploader) y el analista (checklist de "
                    + "faltantes): qué documentos requiere el ramo. Por NOMBRE de ramo, que es lo que la "
                    + "póliza y el expediente tienen a mano. Sin agenda devuelve lista vacía.")
    public List<String> getByBranchName(@RequestParam String branch) {
        return documentRequirementService.getByBranchName(branch);
    }

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
