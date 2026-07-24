package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
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
    private final ImageFraudAnalysisService imageFraudAnalysisService;

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

            ImageForensicReport forensicReport = runImageFraudAnalysis(caseId, response, documents);
            if (forensicReport != null) {
                response = withForensicTraces(response, forensicReport);
            }

            log.info("[ClaimClassificationService] ✓ Classification obtained — caseId={} {} confidence={} latency={}ms",
                    caseId, response.classification(), response.confidence(), latencyMs);

            resultsService.saveResult(caseId, response, forensicReport, latencyMs);

        } catch (Exception e) {
            log.error("[ClaimClassificationService] ✗ Error processing caseId={} after retries — {}",
                    caseId, e.getMessage(), e);
            throw new RuntimeException("Classification failed for case " + caseId + " after retries", e);
        }
    }

    /**
     * Runs the image-fraud cascade for the deep review.
     *
     * <p>Only for the deep review: a Fast Track claim cleared the deterministic gate, so it
     * doesn't warrant the cost of the analysis (embeddings, and possibly an external call).
     * Fraud review is precisely what the non Fast Track route is for.
     *
     * @return the structured report, or null when the analysis didn't run (Fast Track) or found
     *         no images to analyze — the caller persists it as-is (null ⇒ no forensic section)
     */
    private ImageForensicReport runImageFraudAnalysis(
            Long caseId, ClassificationResponse response, List<AttachmentDocument> documents) {

        if (response.deterministicFastTrack()) {
            log.info("[ClaimClassificationService] caseId={} Fast Track — skipping image fraud analysis", caseId);
            return null;
        }

        ImageForensicReport report = imageFraudAnalysisService.analyze(caseId, documents);
        return report.imagesAnalyzed() == 0 ? null : report;
    }

    /** Folds the forensic traces into the classification factors (the analyst's reading of the case). */
    private ClassificationResponse withForensicTraces(ClassificationResponse response, ImageForensicReport report) {
        List<String> traces = imageFraudAnalysisService.renderTraces(report);
        if (traces.isEmpty()) {
            return response;
        }
        List<String> factors = new ArrayList<>(response.factors());
        factors.addAll(traces);

        // toBuilder (not builder): keep riskScore/insuredName the orchestrator attached — only factors change.
        return response.toBuilder()
                .factors(factors)
                .build();
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
