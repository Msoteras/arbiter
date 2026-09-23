package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
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

    private final ClassificationOrchestrator classificationOrchestrator;
    private final ClassificationResultsService resultsService;
    private final CaseOutcomeRepository caseOutcomeRepository;

    /**
     * The retry window is deliberately wide (minutes, not seconds) so a dependency that is restarting
     * or mid-deploy recovers without an analyst pressing retry by hand.
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttemptsExpression = "${arbiter.classification.retry.max-attempts:6}",
            backoff = @Backoff(
                    delayExpression = "${arbiter.classification.retry.initial-delay-ms:15000}",
                    multiplierExpression = "${arbiter.classification.retry.multiplier:2.0}",
                    maxDelayExpression = "${arbiter.classification.retry.max-delay-ms:60000}"
            )
    )
    public void processClaimClassification(Long caseId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClaimClassificationService] ▶ Starting async classification — caseId={} policy='{}' insuredId='{}'",
                caseId, claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            // Only the caseId overload runs the image-fraud cascade.
            ClassificationResponse response = classificationOrchestrator.classify(caseId, claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClaimClassificationService] ✓ Classification obtained — caseId={} {} confidence={} latency={}ms",
                    caseId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(caseId, response, response.forensicReport(), latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing caseId={} after retries — {}",
                    caseId, e.getMessage(), e);
            caseOutcomeRepository.recordClassificationFailure(caseId, classifyFailure(e), e.getMessage());
            throw new RuntimeException("Classification failed for case " + caseId + " after retries", e);
        }
    }

    /**
     * Mirrors {@code retryFor}: only connectivity/5xx is {@code INFRASTRUCTURE}, which is what
     * cases-service's recovery sweep requeues; anything else would fail the same way again.
     */
    private ClassificationFailureReason classifyFailure(Exception e) {
        return (e instanceof HttpServerErrorException || e instanceof ResourceAccessException)
                ? ClassificationFailureReason.INFRASTRUCTURE
                : ClassificationFailureReason.OTHER;
    }
}
