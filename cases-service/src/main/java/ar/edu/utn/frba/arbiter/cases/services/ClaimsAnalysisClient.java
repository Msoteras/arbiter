package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Adapter boundary to classification-service. The mock implementation fakes a
 * synchronous analysis; the real one should call classification-service's
 * POST /api/v1/claims (async, returns a claimId) and poll GET /api/v1/claims/{id}
 * until a result is available — see CLASSIFICATION_SERVICE_URL in application config.
 */
public interface ClaimsAnalysisClient {

    AnalysisResult analyze(CaseRequest request);

    /**
     * Classify a case and persist the result.
     * Mock implementation: synchronous, immediate result.
     * Real implementation: async via REST, queues classification job.
     */
    AnalysisResult analyzeAndPersist(CaseEntity caseEntity, Map<String, MultipartFile> documents);

    /**
     * Poll for updated classification results.
     * Returns true if classification is now available, false if still pending.
     */
    boolean refreshClassification(CaseEntity caseEntity);
}
