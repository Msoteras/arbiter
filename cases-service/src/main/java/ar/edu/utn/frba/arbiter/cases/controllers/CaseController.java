package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Case lifecycle management")
public class CaseController {

    private final CaseService caseService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ASEGURADO', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Create a case",
            description = """
                    Registers a case using the same request structure as the claim flow and triggers
                    an analysis with classification-service, forwarding claimedAmount and any attached
                    documents. Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).
                    """)
    public ResponseEntity<CaseResponse> createCase(
            @RequestPart("case") @Valid CaseRequest request,
            @RequestParam(required = false) Map<String, MultipartFile> documents
    ) {
        CaseResponse response = caseService.createCase(request, documents);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get case by id",
            description = """
                    Returns the stored case, its analysis result and the full status history
                    (every transition with actor, reason and timestamp). Any authenticated role
                    can call this today — there's no owner check yet (the asegurado's identity
                    isn't linked to Case.insuredId), so this only gates "logged in", not "yours".
                    """)
    public ResponseEntity<CaseResponse> getCase(@PathVariable Long caseId) {
        CaseResponse response = caseService.getCase(caseId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List cases",
            description = """
                    Returns cases, most recent first. Optional filters, combinable:
                    `status` (e.g. for the analyst's queue) and `insuredId` (the insured's own
                    cases — until Auth0/JWT integration lands, the caller passes it explicitly).
                    """)
    public ResponseEntity<List<CaseResponse>> listCases(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) String insuredId
    ) {
        List<CaseResponse> response = caseService.listCases(status, insuredId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ASEGURADO', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Upload additional documents",
            description = """
                    Uploads additional documents to an existing case and re-triggers
                    classification. Each part is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).
                    """)
    public ResponseEntity<CaseResponse> uploadDocuments(
            @PathVariable Long caseId,
            @RequestParam Map<String, MultipartFile> documents
    ) {
        CaseResponse response = caseService.addDocumentsAndReclassify(caseId, documents);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/{caseId}/decision")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Persist the analyst's decision",
            description = "Forwards the analyst decision to classification-service so it is persisted in the audit trail.")
    public ResponseEntity<Map<String, Object>> recordDecision(
            @PathVariable Long caseId,
            @RequestBody @Valid AnalystDecisionRequest request
    ) {
        caseService.recordAnalystDecision(caseId, request);
        return ResponseEntity.ok(Map.of(
                "caseId", caseId,
                "status", "decision-recorded"
        ));
    }
}