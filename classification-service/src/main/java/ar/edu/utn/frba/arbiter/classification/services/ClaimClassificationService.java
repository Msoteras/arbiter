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
    private final CaseOutcomeRepository caseOutcomeRepository;

    /**
     * Trigger async classification for a persisted claim.
     * Called from ClaimController after claim is registered.
     * Returns immediately; classification happens in background.
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
    public void processIsolatedClassification(ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClaimClassificationService] ▶ Starting isolated async classification — policy='{}' insuredId='{}'",
                claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            ClassificationResponse response = classificationOrchestrator.classify(claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClaimClassificationService] ✓ Isolated classification obtained — {} confidence={} latency={}ms",
                    response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(null, response, null, latencyMs);

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
     *
     * <p>{@code @Retryable}'s window is wide on purpose (defaults to ~6 attempts, 15s doubling up
     * to 60s between them — several minutes total): the old 3 attempts / 2s-4s backoff (~14s)
     * exhausted long before a restarting container or an in-progress deploy of a dependency (e.g.
     * rules-service) came back, so every blip needed an analyst noticing and pressing retry by
     * hand. Configurable via {@code arbiter.classification.retry.*} rather than hardcoded so the
     * window can be tuned without a redeploy of this module.
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

            // The caseId overload runs the image-fraud cascade and threads it into scoring + the response.
            ClassificationResponse response = classificationOrchestrator.classify(caseId, claim, documents);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClaimClassificationService] ✓ Classification obtained — caseId={} {} confidence={} latency={}ms",
                    caseId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(caseId, response, response.forensicReport(), latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing caseId={} after retries — {}",
                    caseId, e.getMessage(), e);
            // Only recorded when caseId != null — the isolated flow above has no case row to
            // write onto, and recordClassificationFailure is a no-op for a null id.
            caseOutcomeRepository.recordClassificationFailure(caseId, classifyFailure(e), e.getMessage());
            throw new RuntimeException("Classification failed for case " + caseId + " after retries", e);
        }
    }

    /**
     * {@link #processClaimClassification}'s {@code retryFor} already draws this exact line for
     * what's worth retrying automatically — connectivity/5xx from a dependency (rules-service,
     * chiefly) — so reuse it here instead of a second, drifting definition. Everything else
     * (a 4xx, a bug, bad input) is {@code OTHER}: retrying it would just fail the same way again,
     * which is why cases-service's startup recovery sweep only requeues {@code INFRASTRUCTURE}.
     */
    private ClassificationFailureReason classifyFailure(Exception e) {
        return (e instanceof HttpServerErrorException || e instanceof ResourceAccessException)
                ? ClassificationFailureReason.INFRASTRUCTURE
                : ClassificationFailureReason.OTHER;
    }

    /**
     * Recomputes classification + risk for a case and persists a fresh authoritative snapshot in
     * classification_log. Both ends stay in sync without a new cross-service channel: the snapshot
     * is the source of truth and the Case's cached copy refreshes from it via the existing poll
     * (GET /api/v1/claims/{caseId}).
     *
     * <p>Enganche: cases-service's {@code addDocumentsAndReclassify} ("documentación agregada al
     * expediente") already re-triggers this exact flow by re-POSTing to /api/v1/claims and clears
     * the case's cached analysis+risk fields, so the recalculation window reads as "sin scorear"
     * rather than a stale band. There is no dedicated score-only recompute event today; if one is
     * added, subscribe it to this method. Scoring itself lives in {@code RiskScoringService} and is
     * invoked once per classification by the orchestrator — this method does not re-implement it.
     *
     * <p>Requires the {@link ClaimReport} because this module doesn't persist the claim inputs
     * (cases-service owns them); the caller that triggers a recalculation already has them.
     */
    public void recalculate(Long caseId, ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[ClaimClassificationService] ↻ Recalculating classification + risk for caseId={}", caseId);
        processClaimClassification(caseId, claim, documents);
    }
}
