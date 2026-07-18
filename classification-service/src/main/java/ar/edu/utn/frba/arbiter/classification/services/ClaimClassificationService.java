package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Thin façade that exposes the classification entry points expected by the controllers.
 * The actual orchestration is delegated to {@link ClassificationOrchestrator} and the
 * result is persisted so the analyst decision endpoint can work immediately in local/dev flows.
 */
@Service
@RequiredArgsConstructor
public class ClaimClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassificationService.class);

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp");

    private final ClassificationOrchestrator classificationOrchestrator;
    private final ClassificationResultsService resultsService;
    private final ImageEmbeddingService imageEmbeddingService;

    /**
     * Trigger async classification for a persisted claim.
     * Called from ClaimController after claim is registered.
     * Returns immediately; classification happens in background.
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processIsolatedClassification(ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClaimClassificationService] ▶ Starting isolated async classification — policy='{}' insuredId='{}'",
                claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = classificationOrchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClaimClassificationService] ✓ Isolated classification obtained — {} confidence={} latency={}ms",
                    response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(null, response, latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing isolated classification after retries — {}",
                    e.getMessage(), e);
            throw new RuntimeException("Isolated classification failed after retries", e);
        }
    }

    /**
     * Async classification for a real case (POST /api/v1/claims, called by cases-service).
     * Persists the result tagged with the caseId — that's the only signal this module gives
     * back; whoever owns the case lifecycle (cases-service) polls GET /api/v1/claims/{caseId}.
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processClaimClassification(Long caseId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClaimClassificationService] ▶ Starting async classification — caseId={} policy='{}' insuredId='{}'",
                caseId, claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = classificationOrchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            List<String> duplicateFactors = checkDuplicateImages(caseId, documents);
            if (!duplicateFactors.isEmpty()) {
                response = enrichWithDuplicateFactors(response, duplicateFactors);
            }

            log.info("[ClaimClassificationService] ✓ Classification obtained — caseId={} {} confidence={} latency={}ms",
                    caseId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(caseId, response, latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing caseId={} after retries — {}",
                    caseId, e.getMessage(), e);
            throw new RuntimeException("Classification failed for case " + caseId + " after retries", e);
        }
    }

    private List<String> checkDuplicateImages(Long caseId, List<AttachmentDocument> documents) {
        List<String> factors = new ArrayList<>();
        int imageIndex = 0;

        for (AttachmentDocument doc : documents) {
            if (!IMAGE_CONTENT_TYPES.contains(doc.contentType())) {
                continue;
            }
            String imageBase64 = Base64.getEncoder().encodeToString(doc.content());
            String attachmentLabel = doc.type() + "-" + imageIndex++;

            try {
                List<DuplicateImageMatch> matches = imageEmbeddingService.processAndFindDuplicates(
                        caseId, attachmentLabel, doc.type(), imageBase64);

                for (DuplicateImageMatch match : matches) {
                    factors.add(String.format(
                            "⚠ Imagen '%s' similar (%.1f%%) a adjunto del siniestro #%d ('%s')",
                            doc.type(),
                            match.similarity() * 100,
                            match.matchedCaseId(),
                            match.matchedFilename()));
                }
            } catch (Exception e) {
                log.warn("[ClaimClassificationService] Embedding check failed for attachment '{}' — {}",
                        attachmentLabel, e.getMessage());
            }
        }

        return factors;
    }

    private ClassificationResponse enrichWithDuplicateFactors(
            ClassificationResponse original,
            List<String> duplicateFactors
    ) {
        List<String> allFactors = new ArrayList<>(original.factors());
        allFactors.addAll(duplicateFactors);

        return ClassificationResponse.builder()
                .classification(original.classification())
                .factors(allFactors)
                .confidence(original.confidence())
                .deterministicFastTrack(original.deterministicFastTrack())
                .build();
    }
}
