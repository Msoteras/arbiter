package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Case lifecycle management")
public class CaseController {

    private final CaseService caseService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
    @Operation(summary = "Get case by id",
            description = "Returns the stored case and its analysis result.")
    public ResponseEntity<CaseResponse> getCase(@PathVariable Long caseId) {
        CaseResponse response = caseService.getCase(caseId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
}