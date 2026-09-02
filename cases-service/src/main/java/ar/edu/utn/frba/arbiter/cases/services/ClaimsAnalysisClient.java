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

    /**
     * Triggers classification for a case, forwarding its data and the full set of
     * accumulated documents to classification-service (tagged with the case id).
     */
    AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents);

    /**
     * Same trigger as {@link #analyzeAndPersist}, for callers with no HTTP request behind them
     * (e.g. {@code ClassificationRefreshScheduler}'s infrastructure-failure recovery sweep) —
     * signs a service token instead of forwarding a caller's JWT, since there isn't one to forward.
     */
    AnalysisResult analyzeAndPersistAsSystem(Case caseRecord, List<CaseDocument> documents);

    /**
     * Single, non-blocking attempt to pull the classification result.
     * Returns true if classification is now available, false if still pending.
     */
    boolean refreshClassification(Case caseRecord);

    /**
     * Forwards the analyst's decision to classification-service so it is persisted
     * in the audit trail (llm_analysis + llm_reason).
     *
     * @return the id of the {@code case_classification} row it created, to be stored on
     *         {@code cases.classification_id}; null if the response didn't carry one.
     */
    Long forwardAnalystDecision(Long caseId, AnalystDecisionRequest request);

    /**
     * Registers a fraud record against an insured. Rides this boundary and not one of its own
     * because classification-service owns the record: it's the module that reads it while scoring
     * the insured's next claim.
     */
    FraudRecordResponse registerFraudRecord(FraudRecordRequest request);

    /** The insured's fraud records, lapsed ones included (each says whether it's still in force). */
    List<FraudRecordResponse> fraudRecordsOf(String insuredDni);

    /**
     * Rules evaluated for the case, passes included — a Fast Track carries them too, all passing.
     * Empty when none ran; {@code null} when they couldn't be read.
     */
    List<RuleResultResponse> ruleResultsOf(Long caseId);
}
