package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;

import java.util.List;

/**
 * Adapter boundary to classification-service.
 * Implementations call classification-service's POST /api/v1/claims (async, returns a caseId)
 * and poll GET /api/v1/claims/{caseId} until a result is available.
 */
public interface ClaimsAnalysisClient {

    /** Sends the case data and the full set of accumulated documents, not just the new ones. */
    AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents);

    /**
     * Same as {@link #analyzeAndPersist}, for callers with no HTTP request behind them: signs a
     * service token since there is no caller JWT to forward.
     */
    AnalysisResult analyzeAndPersistAsSystem(Case caseRecord, List<CaseDocument> documents);

    /** Single, non-blocking attempt; returns false while classification is still pending. */
    boolean refreshClassification(Case caseRecord);

    /**
     * Forwards the analyst's decision so classification-service records it in the audit trail.
     *
     * @return the id of the {@code case_classification} row it created, to be stored on
     *         {@code cases.classification_id}; null if the response didn't carry one.
     */
    Long forwardAnalystDecision(Long caseId, AnalystDecisionRequest request);

    /** classification-service owns fraud records: it reads them while scoring the insured's next claim. */
    FraudRecordResponse registerFraudRecord(FraudRecordRequest request);

    /** The insured's fraud records, lapsed ones included (each says whether it's still in force). */
    List<FraudRecordResponse> fraudRecordsOf(String insuredDni);

    /**
     * Rules evaluated for the case, passes included — a Fast Track carries them too, all passing.
     * Empty when none ran; {@code null} when they couldn't be read.
     */
    List<RuleResultResponse> ruleResultsOf(Long caseId);
}
