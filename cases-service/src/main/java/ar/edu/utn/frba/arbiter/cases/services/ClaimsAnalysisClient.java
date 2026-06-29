package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Adapter boundary to classification-service. Always calls the real
 * POST /api/v1/claims (async, returns a claimId) and polls
 * GET /api/v1/claims/{id} until a result is available — see
 * arbiter.classification-service.url in application config.
 */
public interface ClaimsAnalysisClient {

    /**
     * Triggers classification for a case, forwarding its claimedAmount and
     * documents to classification-service, and persists the returned claimId.
     */
    AnalysisResult analyzeAndPersist(CaseEntity caseEntity, Map<String, MultipartFile> documents);

    /**
     * Polls for updated classification results.
     * Returns true if classification is now available, false if still pending.
     */
    boolean refreshClassification(CaseEntity caseEntity);
}
