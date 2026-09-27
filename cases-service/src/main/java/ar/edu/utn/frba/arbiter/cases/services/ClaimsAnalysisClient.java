package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;

import java.util.List;

/** Adapter to classification-service: POST /api/v1/claims enqueues, GET /api/v1/claims/{caseId} polls. */
public interface ClaimsAnalysisClient {

    /** Sends the case data and the full set of accumulated documents, not just the new ones. */
    AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents);

    /** For callers with no HTTP request behind them: signs a service token instead of forwarding a JWT. */
    AnalysisResult analyzeAndPersistAsSystem(Case caseRecord, List<CaseDocument> documents);

    /** Single, non-blocking attempt; returns false while classification is still pending. */
    boolean refreshClassification(Case caseRecord);

    /**
     * @return the {@code case_classification} row created, for {@code cases.classification_id}; null if
     *         the response carried none
     */
    Long forwardAnalystDecision(Long caseId, AnalystDecisionRequest request);

    /** classification-service owns fraud records: it reads them while scoring the insured's next claim. */
    FraudRecordResponse registerFraudRecord(FraudRecordRequest request);

    /** The insured's fraud records, lapsed ones included (each says whether it's still in force). */
    List<FraudRecordResponse> fraudRecordsOf(String insuredDni);

    /** Passes included (a Fast Track carries them too). Empty when none ran; {@code null} when unreadable. */
    List<RuleResultResponse> ruleResultsOf(Long caseId);
}
