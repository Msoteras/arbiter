package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassificationJob {

    private static final Logger log = LoggerFactory.getLogger(ClassificationJob.class);

    private final ClassificationOrchestrator orchestrator;
    private final ClassificationResultsService resultsService;

    /**
     * Async classification for the isolated test endpoint (POST /api/v1/classifications):
     * there's no persisted Claim, so the result is still audited in classification_log
     * with claimId null (see GET /api/v1/classifications/results).
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processIsolatedClassification(ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClassificationJob] ▶ Starting isolated async classification — policy='{}' insuredId='{}'",
                claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = orchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClassificationJob] ✓ Isolated classification obtained — {} confidence={} latency={}ms",
                    response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(null, response, latencyMs);

        } catch (Exception e) {
            log.error("[ClassificationJob] ✗ Error processing isolated classification after retries — {}",
                    e.getMessage(), e);
            throw new RuntimeException("Isolated classification failed after retries", e);
        }
    }

    /**
     * Async classification for a real, persisted claim (POST /api/v1/claims). Persists the
     * result tagged with the claimId — that's the only signal this module gives back; whoever
     * owns the claim's lifecycle (cases-service) polls GET /api/v1/claims/{id} for it.
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processClaimClassification(Long claimId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClassificationJob] ▶ Starting async classification — claimId={} policy='{}' insuredId='{}'",
                claimId, claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = orchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClassificationJob] ✓ Classification obtained — claimId={} {} confidence={} latency={}ms",
                    claimId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(claimId, response, latencyMs);

        } catch (Exception e) {
            log.error("[ClassificationJob] ✗ Error processing claimId={} after retries — {}",
                    claimId, e.getMessage(), e);
            throw new RuntimeException("Classification failed for claim " + claimId + " after retries", e);
        }
    }
}
