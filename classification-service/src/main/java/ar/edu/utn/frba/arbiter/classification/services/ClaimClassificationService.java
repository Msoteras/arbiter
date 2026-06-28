package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service that wraps {@link ClassificationJob} to properly handle @Async execution
 * from the HTTP layer. Required because @Async doesn't work when called from the
 * same bean instance (Spring proxy limitation).
 *
 * This service is injected into the controller, so Spring intercepts the call
 * and delegates it to the async executor.
 */
@Service
@RequiredArgsConstructor
public class ClaimClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassificationService.class);

    private final ClassificationJob classificationJob;

    /**
     * Trigger async classification for a persisted claim.
     * Called from ClaimController after claim is registered.
     * Returns immediately; classification happens in background.
     */
    @Async("classificationExecutor")
    public void classifyAsync(Long claimId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.debug("[ClaimClassificationService] Delegating async classification to ClassificationJob — claimId={}", claimId);
        classificationJob.processClaimClassification(claimId, claim, documents);
    }
}
