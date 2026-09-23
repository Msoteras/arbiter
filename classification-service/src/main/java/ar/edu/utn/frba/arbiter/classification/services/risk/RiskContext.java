package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;

import java.util.List;
import java.util.Map;

/**
 * Everything the evaluators need, reusing the data the orchestrator already fetched so no evaluator
 * calls an adapter again. A null {@code imageFraud} or empty {@code documents} means "not analyzed",
 * which evaluators treat as not evaluable, never as "nothing wrong".
 */
public record RiskContext(
        ClaimReport claim,
        InsuredPolicy policy,
        InsuredHistory history,
        BusinessRules rules,
        ImageForensicReport imageFraud,
        Map<String, DocumentExtraction> documents,
        List<InsuredFraudRecord> fraudRecords
) {

    public RiskContext {
        documents = documents == null ? Map.of() : Map.copyOf(documents);
        fraudRecords = fraudRecords == null ? List.of() : List.copyOf(fraudRecords);
    }

    public RiskContext(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        this(claim, policy, history, rules, null, Map.of(), List.of());
    }

    public RiskContext(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules,
                       ImageForensicReport imageFraud, Map<String, DocumentExtraction> documents) {
        this(claim, policy, history, rules, imageFraud, documents, List.of());
    }
}
