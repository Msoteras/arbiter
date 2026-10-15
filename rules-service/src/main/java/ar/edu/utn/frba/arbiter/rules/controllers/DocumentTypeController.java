package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.common.dto.DocumentTypeResponse;
import ar.edu.utn.frba.arbiter.rules.services.DocumentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The document catalog (see {@link DocumentTypeService}). Read-only: it is loaded by script. */
@RestController
@RequestMapping("/api/v1/rules/document-types")
@RequiredArgsConstructor
@Tag(name = "Tipos de documento", description = "Catálogo de documentos y los que admite cada ramo")
public class DocumentTypeController {

    private final DocumentTypeService documentTypeService;

    // Every role reads it: it names the documents on every screen, and nothing in it is sensitive.
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Tipos de documento",
            description = "Código y nombre de cada documento. Con branchId, solo los que la agenda y el "
                    + "Fast Track de ese ramo pueden pedir; sin él, el catálogo entero.")
    public List<DocumentTypeResponse> list(@RequestParam(required = false) Long branchId) {
        return branchId == null ? documentTypeService.list() : documentTypeService.listForBranch(branchId);
    }
}
