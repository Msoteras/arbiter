package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.classification.services.ClassificationResultsService;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.services.ClaimClassificationService;
import ar.edu.utn.frba.arbiter.classification.services.MultipartDocumentMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Internal REST API for claim classification — this is the contract cases-service's
 * ClaimsAnalysisClient calls to get a case classified. This module does not persist the
 * claim/case itself (cases-service owns it); it only runs the analysis and keeps the
 * audit log, correlated to the caller's {@code caseId}.
 */
@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
@Tag(name = "Claims", description = "Claim classification for a case (internal, module-to-module API).")
public class ClaimController {

    private final ClaimClassificationService claimClassificationService;
    private final ClassificationResultsService resultsService;
    private final MultipartDocumentMapper documentMapper;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Trigger the classification of a case's claim",
            description = """
                    Kicks off classification asynchronously (the deterministic Fast Track gate
                    first, LLM fallback if it doesn't qualify) and tags the result with the caller's
                    `caseId`. Returns 202 right away — poll GET /api/v1/claims/{caseId} for the result.

                    Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `item_photo`), and `documentIds` carries
                    the matching `case_documents` id under the same key — image analysis stores
                    it so a duplicate match can point at the exact file.
                    """,
            responses = {
                    @ApiResponse(responseCode = "202", description = "Analysis in progress"),
                    @ApiResponse(responseCode = "422", description = "A document could not be read"),
                    @ApiResponse(responseCode = "400", description = "Invalid claim data")
            }
    )
    public ResponseEntity<Map<String, Object>> classify(
            @RequestParam("caseId") Long caseId,
            @RequestPart("claim") @Valid ClaimReport claim,
            @RequestParam(required = false) Map<String, MultipartFile> documents,
            @RequestPart(value = "documentIds", required = false) Map<String, Long> documentIds
    ) {
        claimClassificationService.processClaimClassification(
                caseId, claim, documentMapper.toAttachmentDocuments(documents, documentIds));

        return ResponseEntity
                .status(HttpStatusCode.valueOf(202))
                .location(URI.create("/api/v1/claims/" + caseId))
                .body(Map.of(
                        "caseId", caseId,
                        "message", "Analysis in progress.",
                        "checkResultAt", "/api/v1/claims/" + caseId
                ));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get a case's classification",
            description = "Classification fields are null until the analysis finishes — poll until they appear."
    )
    @ApiResponse(responseCode = "200", description = "Classification found (may still be null/pending)")
    public ResponseEntity<ClaimResponse> getStatus(@PathVariable Long caseId) {
        return ResponseEntity.ok(resultsService.getStatus(caseId));
    }

    // Case-level access is cases-service's call: it's the one that knows the assigned analyst.
    @GetMapping("/{caseId}/rule-results")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Rules evaluated for a case",
            description = "Every hard rule that ran, passes included. Empty when none did (Fast Track)."
    )
    @ApiResponse(responseCode = "200", description = "Rules evaluated, in evaluation order")
    public ResponseEntity<List<RuleResultResponse>> getRuleResults(@PathVariable Long caseId) {
        return ResponseEntity.ok(resultsService.getRuleResults(caseId));
    }

    // Solo token de servicio (sin claim `rol`): el analystId viaja en el body y cases-service es el
    // único que lo resuelve contra claims_analyst.
    @PostMapping("/{caseId}/decision")
    @PreAuthorize("authentication.authorities.isEmpty()")
    @Operation(
            summary = "Persist the analyst's final decision",
            description = "Stores the analyst's verdict for the classification already produced for the case."
    )
    @ApiResponse(responseCode = "200", description = "Decision persisted")
    public ResponseEntity<Map<String, Object>> recordDecision(
            @PathVariable Long caseId,
            @RequestBody @Valid AnalystDecisionRequest request
    ) {
        Long classificationId = resultsService.recordAnalystDecision(caseId, request);
        return ResponseEntity.ok(Map.of(
                "caseId", caseId,
                "status", "decision-recorded",
                // cases-service stores this on cases.classification_id — see Case.classificationId.
                "classificationId", classificationId
        ));
    }
}
