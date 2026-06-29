package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.services.ClaimClassificationService;
import ar.edu.utn.frba.arbiter.classification.services.ClassificationResultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/classifications")
@RequiredArgsConstructor
@Tag(name = "Classifications", description = "Isolated classification testing — runs the full analysis " +
        "flow (Fast Track gate + LLM fallback) without going through claim creation/persistence.")
public class ClassificationController {

    private final ClaimClassificationService claimClassificationService;
    private final ClassificationResultsService resultsService;
    private final MultipartDocumentMapper documentMapper;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Classify a claim in isolation, with its documents",
            description = """
                    Runs asynchronously: the deterministic Fast Track gate against the insurer's
                    business rules first; if the claim doesn't qualify, extracts the attached documents
                    with the vision model (OCR) and falls back to full LLM classification with the
                    structured prompt. Check progress/results via GET /api/v1/classifications/results.

                    Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `item_photo`) — that key tells the OCR step
                    how to read the file. Skipped entirely if Fast Track already resolves the case.

                    For testing the analysis flow in isolation, without creating/persisting a claim.
                    """,
            responses = {
                    @ApiResponse(responseCode = "202", description = "Analysis in progress"),
                    @ApiResponse(responseCode = "422", description = "A document could not be read"),
                    @ApiResponse(responseCode = "400", description = "Invalid claim data")
            }
    )
    public ResponseEntity<Map<String, Object>> classify(
            @RequestPart("claim") @Valid ClaimReport claim,
            @RequestParam(required = false) Map<String, MultipartFile> documents
    ) {
        claimClassificationService.processIsolatedClassification(claim, documentMapper.toAttachmentDocuments(documents));

        return ResponseEntity
                .accepted()
                .body(Map.of(
                        "status", "PENDING_ANALYSIS",
                        "message", "Isolated analysis in progress (deterministic Fast Track or LLM as applicable).",
                        "checkResultAt", "/api/v1/classifications/results"
                ));
    }

    @GetMapping("/results")
    @Operation(summary = "Get classification results table")
    public ResponseEntity<String> getResults() {
        String content = resultsService.getContent();
        return ResponseEntity
                .ok()
                .contentType(MediaType.TEXT_MARKDOWN)
                .body(content);
    }
}
