package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.classification.services.ClaimClassificationService;
import ar.edu.utn.frba.arbiter.classification.services.ClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;

/**
 * Internal REST API for claim reports — this is the contract other modules (today:
 * cases-service's ClaimsAnalysisClient) call to get a claim classified.
 * Not the public intake form for the insured — that's cases-service's job.
 */
@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
@Tag(name = "Claims", description = "Claim report intake for classification (internal, module-to-module API).")
public class ClaimController {

    private final ClaimService claimService;
    private final ClaimClassificationService claimClassificationService;
    private final MultipartDocumentMapper documentMapper;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Register a claim report and trigger its classification",
            description = """
                    Persists the claim and immediately kicks off classification asynchronously
                    (the deterministic Fast Track gate first, LLM fallback if it doesn't qualify).
                    Returns 202 with the claimId right away — poll GET /api/v1/claims/{id}
                    for the result.

                    Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `item_photo`).
                    """,
            responses = {
                    @ApiResponse(responseCode = "202", description = "Claim registered, analysis in progress"),
                    @ApiResponse(responseCode = "422", description = "A document could not be read"),
                    @ApiResponse(responseCode = "400", description = "Invalid claim data")
            }
    )
    public ResponseEntity<Map<String, Object>> register(
            @RequestPart("claim") @Valid ClaimReport claim,
            @RequestParam(required = false) Map<String, MultipartFile> documents
    ) {
        Long claimId = claimService.register(claim);
        claimClassificationService.classifyAsync(claimId, claim, documentMapper.toAttachmentDocuments(documents));

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/claims/" + claimId))
                .body(Map.of(
                        "claimId", claimId,
                        "message", "Claim registered, analysis in progress.",
                        "checkResultAt", "/api/v1/claims/" + claimId
                ));
    }

    @GetMapping("/{claimId}")
    @Operation(
            summary = "Get a claim's classification",
            description = "Classification fields are null until the analysis finishes — poll until they appear."
    )
    @ApiResponse(responseCode = "200", description = "Claim found (classification may still be null/pending)")
    @ApiResponse(responseCode = "422", description = "Claim does not exist")
    public ResponseEntity<ClaimResponse> getStatus(@PathVariable Long claimId) {
        return ResponseEntity.ok(claimService.getStatus(claimId));
    }
}
