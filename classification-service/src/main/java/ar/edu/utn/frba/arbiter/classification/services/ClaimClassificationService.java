package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
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

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClaimClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimClassificationService.class);

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
        log.info("[ClaimClassificationService] ▶ Starting isolated async classification — policy='{}' insuredId='{}'",
                claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = orchestrator.classify(claim, documents);
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

            ClassificationResponse response = orchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClaimClassificationService] ✓ Classification obtained — caseId={} {} confidence={} latency={}ms",
                    caseId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(caseId, response, latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing caseId={} after retries — {}",
                    caseId, e.getMessage(), e);
            throw new RuntimeException("Classification failed for case " + caseId + " after retries", e);
        }
    }
}
