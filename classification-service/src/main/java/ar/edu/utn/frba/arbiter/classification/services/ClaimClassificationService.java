package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin façade that exposes the classification entry points expected by the controllers.
 * The actual orchestration is delegated to {@link ClassificationOrchestrator} and the
 * result is persisted so the analyst decision endpoint can work immediately in local/dev flows.
 */
@Service
@RequiredArgsConstructor
public class ClaimClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassificationService.class);

    private final ClassificationOrchestrator classificationOrchestrator;
    private final ClassificationResultsService resultsService;

    /**
     * Trigger async classification for a persisted claim.
     * Called from ClaimController after claim is registered.
     * Returns immediately; classification happens in background.
     */
    @Async("classificationExecutor")
    public void classifyAsync(Long claimId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.debug("[ClaimClassificationService] Delegating async classification to orchestrator — claimId={}", claimId);
        saveClassificationResult(claimId, claim, documents);
    }

    /**
     * Trigger classification for an isolated claim used by the testing endpoint.
     */
    public void processIsolatedClassification(ClaimReport claim, List<AttachmentDocument> documents) {
        log.debug("[ClaimClassificationService] Processing isolated classification for claim with {} document(s)", documents.size());
        saveClassificationResult(null, claim, documents);
    }

    private void saveClassificationResult(Long claimId, ClaimReport claim, List<AttachmentDocument> documents) {
        try {
            ClassificationResponse response = classificationOrchestrator.classify(claim, documents);
            if (claimId != null) {
                resultsService.saveResult(claimId, response, 0L);
            }
        } catch (Exception exception) {
            log.warn("[ClaimClassificationService] Fallback classification used for claim {} due to {}",
                    claimId, exception.getMessage());
            ClassificationResponse fallback = ClassificationResponse.builder()
                    .classification(Classification.LLM_RECOMIENDA_APROBAR)
                    .factors(List.of("Clasificación de desarrollo: resultado provisional"))
                    .confidence(0.75)
                    .deterministicFastTrack(false)
                    .build();
            if (claimId != null) {
                resultsService.saveResult(claimId, fallback, 0L);
            }
        }
    }
}
