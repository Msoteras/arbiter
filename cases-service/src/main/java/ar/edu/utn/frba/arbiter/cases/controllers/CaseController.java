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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
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
                    Devuelve expedientes paginados, más recientes primero por defecto. Filtros
                    opcionales y combinables: `status`, `claimCause` (tipo de siniestro /
                    HechoGenerador), `policyNumber`, `insuredId` (expedientes del asegurado —
                    hasta que Auth0/JWT integration lands, el caller lo pasa explícito) y el rango
                    `eventDateFrom`/`eventDateTo` (ISO `yyyy-MM-dd`, inclusive en ambos extremos)
                    sobre la fecha del hecho. Paginación estándar de Spring Data: `page`, `size`,
                    `sort` (ej. `sort=eventDate,desc`).

                    No filtra por aseguradora/rol del usuario autenticado: depende de auth-service,
                    que todavía no está levantado (ver GAPS-FLUJO.md, Gap F).
                    """)
    public ResponseEntity<Page<CaseResponse>> listCases(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) String claimCause,
            @RequestParam(required = false) String policyNumber,
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateTo,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CaseResponse> response = caseService.listCases(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, pageable);
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