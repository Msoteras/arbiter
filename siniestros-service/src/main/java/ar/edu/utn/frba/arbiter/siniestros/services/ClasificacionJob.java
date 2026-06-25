package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
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
public class ClasificacionJob {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionJob.class);

    private final ClasificacionOrchestrator orchestrator;
    private final ClasificacionResultsService resultsService;

    /**
     * Clasificación asincrónica para el endpoint de testeo aislado (POST /api/v1/classifications):
     * no hay un Siniestro persistido, así que el resultado se audita en clasificacion_log con
     * siniestroId null (ver GET /api/v1/classifications/results).
     */
    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processIsolatedClassification(DenunciaSiniestro claim, List<AttachmentDocument> documents) {
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
}
