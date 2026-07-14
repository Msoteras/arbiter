package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;

import java.util.List;

/**
 * Adapter boundary to classification-service.
 * Implementations call classification-service's POST /api/v1/claims (async, returns a caseId)
 * and poll GET /api/v1/claims/{caseId} until a result is available.
 */
public interface ClaimsAnalysisClient {

    /**
     * Triggers classification for a case, forwarding its data and the full set of
     * accumulated documents to classification-service (tagged with the case id).
     */
    AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents);

    /**
     * Single, non-blocking attempt to pull the classification result.
     * Returns true if classification is now available, false if still pending.
     */
    boolean refreshClassification(Case caseRecord);

    /**
     * Forwards the analyst's decision to classification-service so it is persisted
     * in the audit trail (ClassificationLog).
     */
    void forwardAnalystDecision(Long caseId, AnalystDecisionRequest request);
}
