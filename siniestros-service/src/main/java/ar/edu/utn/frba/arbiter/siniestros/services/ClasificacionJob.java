package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
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

@Service
@RequiredArgsConstructor
public class ClasificacionJob {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionJob.class);

    private final SiniestroClassifier classifier;
    private final ClasificacionOrquestador orchestrator;
    private final ResultadosClasificacionService resultsService;

    @Async("classificationExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void processClassification(Long claimId, DenunciaSiniestro claim) {
        log.info("[ClassificationJob] ▶ Starting async classification — claimId={} policy='{}' insuredId='{}'",
                claimId, claim.policyNumber(), claim.insuredId());

        try {
            long start = System.currentTimeMillis();

            log.debug("[ClassificationJob] → Orchestrating: fetching policy, history, rules...");
            ClasificacionResponse response = orchestrator.classify(claim);
            long latencyMs = System.currentTimeMillis() - start;

            log.info("[ClassificationJob] ✓ Classification obtained — claimId={} classification={} confidence={:.2f} latency={}ms",
                    claimId, response.classification(), response.confidence(), latencyMs);

            log.debug("[ClassificationJob] → Saving result...");
            resultsService.saveResult(
                    claimId,
                    claim.policyNumber(),
                    claim.insuredId(),
                    response.classification(),
                    response.confidence(),
                    response.factors(),
                    latencyMs
            );

            log.info("[ClassificationJob] ✓ DONE — claimId={} {} | Confidence: {:.2f} | Latency: {}ms",
                    claimId, response.classification(), response.confidence(), latencyMs);
            log.debug("[ClassificationJob]   Factors: {}", response.factors());

        } catch (Exception e) {
            log.error("[ClassificationJob] ✗ Error processing claim {} after retries — {}",
                    claimId, e.getMessage(), e);
            throw new RuntimeException("Classification failed for claim " + claimId + " after retries", e);
        }
    }
}
