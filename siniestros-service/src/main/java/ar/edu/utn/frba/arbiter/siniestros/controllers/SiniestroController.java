package ar.edu.utn.frba.arbiter.siniestros.controllers;

import ar.edu.utn.frba.arbiter.siniestros.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.exceptions.ClasificacionInvalidaException;
import ar.edu.utn.frba.arbiter.siniestros.services.ClaimsRegistry;
import ar.edu.utn.frba.arbiter.siniestros.services.ClasificacionJob;
import ar.edu.utn.frba.arbiter.siniestros.services.ResultadosClasificacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
@Tag(name = "Claims", description = "Claims lifecycle management")
public class SiniestroController {

    private static final Logger log = LoggerFactory.getLogger(SiniestroController.class);

    private final ClasificacionJob clasificacionJob;
    private final ResultadosClasificacionService resultsService;
    private final ClaimsRegistry claimsRegistry;
    private final DocumentAnalyzer documentAnalyzer;
    private final AtomicLong claimCounter = new AtomicLong(0);
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Create a claim report",
            description = "Receives claim data together with its initial attachments (can be zero), " +
                    "analyzes each attachment with the vision model (Ollama + Qwen3-VL) and triggers " +
                    "background classification immediately. Returns 202 Accepted with the claimId. " +
                    "More attachments can be added later via POST /{claimId}/attachments " +
                    "(e.g. if the classification comes back as FALTA_DOCUMENTACION)."
    )
    @ApiResponse(responseCode = "202", description = "Claim accepted, classification in progress")
    @ApiResponse(responseCode = "400", description = "Invalid or duplicate claim data")
    public ResponseEntity<Map<String, Object>> createClaim(
            @RequestPart("claim") @Valid DenunciaSiniestro claim,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        String claimKey = generateKey(claim);

        log.info("[SiniestroController] New claim received — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        if (processed.contains(claimKey)) {
            log.warn("[SiniestroController] REJECTED: Duplicate claim — policy='{}' insuredId='{}'",
                    claim.policyNumber(), claim.insuredId());
            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error", "DUPLICATE",
                            "message", "This claim has already been processed.",
                            "policyNumber", claim.policyNumber(),
                            "insuredId", claim.insuredId()
                    ));
        }

        Long claimId = claimCounter.incrementAndGet();
        processed.add(claimKey);
        claimsRegistry.register(claimId, claim);

        analyzeAndClassify(claimId, files);

        log.info("[SiniestroController] Claim accepted — claimId={} policy='{}' insuredId='{}'",
                claimId, claim.policyNumber(), claim.insuredId());

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/claims/" + claimId + "/result"))
                .body(Map.of(
                        "claimId", claimId,
                        "status", "PENDING_CLASSIFICATION",
                        "message", "Claim accepted. Classification in progress (~15-30 seconds).",
                        "uploadMoreAttachmentsAt", "/api/v1/claims/" + claimId + "/attachments",
                        "checkResultAt", "/api/v1/claims/results"
                ));
    }

    @PostMapping(value = "/{claimId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Add attachments to an existing claim and reclassify",
            description = "Receives one or more additional documents for a claim that already exists " +
                    "(e.g. the insured is providing what was missing after a FALTA_DOCUMENTACION result). " +
                    "Each file is analyzed with the vision model, accumulated together with whatever was " +
                    "already extracted, and background classification is triggered again with the updated context."
    )
    @ApiResponse(responseCode = "202", description = "Attachments analyzed, reclassification in progress")
    @ApiResponse(responseCode = "422", description = "Claim does not exist")
    public ResponseEntity<Map<String, Object>> uploadAttachments(
            @PathVariable Long claimId,
            @RequestParam("files") List<MultipartFile> files
    ) {
        log.info("[SiniestroController] Analyzing {} additional attachment(s) for claimId={}", files.size(), claimId);

        analyzeAndClassify(claimId, files);

        return ResponseEntity
                .accepted()
                .body(Map.of(
                        "claimId", claimId,
                        "status", "PENDING_CLASSIFICATION",
                        "message", "Attachments analyzed. Reclassification in progress (~15-30 seconds).",
                        "checkResultAt", "/api/v1/claims/results"
                ));
    }

    private void analyzeAndClassify(Long claimId, List<MultipartFile> files) {
        if (files != null) {
            files.forEach(file -> analyze(claimId, file));
        }

        DenunciaSiniestro claimSnapshot = claimsRegistry.snapshot(claimId);
        clasificacionJob.processClassification(claimId, claimSnapshot);

        log.debug("[SiniestroController] Async job triggered for claimId={}", claimId);
    }

    private void analyze(Long claimId, MultipartFile file) {
        try {
            String extractedText = documentAnalyzer.extractText(file.getBytes(), file.getContentType());
            claimsRegistry.addExtractedText(claimId, extractedText);
        } catch (IOException e) {
            throw new ClasificacionInvalidaException("Could not read attachment '" + file.getOriginalFilename() + "'", e);
        }
    }

    private String generateKey(DenunciaSiniestro claim) {
        return claim.policyNumber() + "|" + claim.insuredId() + "|" +
               claim.branch() + "|" + claim.claimCause();
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
