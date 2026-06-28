package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;

/**
 * Adapter boundary to classification-service. The mock implementation fakes a
 * synchronous analysis; the real one should call classification-service's
 * POST /api/v1/claims (async, returns a claimId) and poll GET /api/v1/claims/{id}
 * until a result is available — see CLASSIFICATION_SERVICE_URL in application config.
 */
public interface ClaimsAnalysisClient {

    AnalysisResult analyze(CaseRequest request);
}
