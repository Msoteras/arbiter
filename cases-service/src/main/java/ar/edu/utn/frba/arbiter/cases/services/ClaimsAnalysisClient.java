package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Adapter boundary to classification-service. Fires the async classification
 * (POST /api/v1/claims, correlated by the case id) and later pulls the result
 * (GET /api/v1/claims/{caseId}) — see arbiter.classification-service.url in config.
 */
public interface ClaimsAnalysisClient {

    /**
     * Triggers classification for a case, forwarding its data and documents to
     * classification-service (tagged with the case id), and marks the case pending.
     */
    AnalysisResult analyzeAndPersist(CaseEntity caseEntity, Map<String, MultipartFile> documents);

    /**
     * Single, non-blocking attempt to pull the classification result.
     * Returns true if classification is now available, false if still pending.
     */
    boolean refreshClassification(CaseEntity caseEntity);
}