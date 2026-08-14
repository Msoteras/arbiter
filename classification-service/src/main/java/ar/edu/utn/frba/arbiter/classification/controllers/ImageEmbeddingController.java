package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.services.ImageEmbeddingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/image-embeddings")
@RequiredArgsConstructor
@Tag(name = "Image Embeddings (PoC)", description = "Duplicate image detection via pgvector + CLIP")
public class ImageEmbeddingController {

    private static final Logger log = LoggerFactory.getLogger(ImageEmbeddingController.class);

    private final ImageEmbeddingService imageEmbeddingService;

    @PostMapping(value = "/check-duplicate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Check if an image has been submitted before",
            description = """
                    Generates a deterministic 512-dim embedding for the uploaded image via the
                    CLIP sidecar (embedding-service), persists it, and searches for similar
                    images already stored. Returns any matches above the configured similarity
                    threshold (default 0.90).
                    """
    )
    public ResponseEntity<Map<String, Object>> checkDuplicate(
            @RequestParam("caseId") Long caseId,
            @RequestParam(value = "attachmentLabel", required = false) String attachmentLabel,
            @RequestPart("image") MultipartFile image
    ) throws IOException {
        log.info("[ImageEmbeddingController] Received image: name='{}' contentType='{}' size={} bytes",
                image.getOriginalFilename(), image.getContentType(), image.getSize());

        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El archivo 'image' está vacío o no se adjuntó. "
                            + "Verificá que en Postman el campo 'image' del form-data tenga un archivo seleccionado."
            ));
        }

        String imageBase64 = Base64.getEncoder().encodeToString(image.getBytes());
        log.info("[ImageEmbeddingController] Base64 length: {} chars", imageBase64.length());

        String resolvedLabel = (attachmentLabel != null && !attachmentLabel.isBlank())
                ? attachmentLabel : "manual-check";

        // caseDocumentId null: the image arrives loose via multipart, there's no case_documents row
        // to anchor it to — it's compared anyway but the analysis isn't persisted.
        List<DuplicateImageMatch> matches = imageEmbeddingService.processAndFindDuplicates(
                caseId, null, resolvedLabel, imageBase64).duplicates();

        return ResponseEntity.ok(Map.of(
                "caseId", caseId,
                "filename", image.getOriginalFilename() != null ? image.getOriginalFilename() : "unknown",
                "duplicatesFound", matches.size(),
                "matches", matches
        ));
    }
}
