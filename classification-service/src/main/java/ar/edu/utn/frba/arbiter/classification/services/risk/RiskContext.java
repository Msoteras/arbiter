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
 * Everything a risk evaluator needs to grade a claim, assembled from the SAME data the
 * {@code ClassificationOrchestrator} already fetched for the classification flow (the claim
 * plus the insured's policy, history and business rules). The score is computed downstream of
 * the classification off this context — no evaluator hits {@code InsurerAdapter} again.
 *
 * <p>{@code imageFraud} carries the pre-computed image-fraud analysis (the cascade already ran
 * once, off the same flow). Null when it didn't run (Fast Track, or no images) — the image
 * evaluators treat that as "not evaluable" and contribute 0.
 *
 * <p>{@code documents} carries what the vision pass read off each attachment, indexed by document
 * type. Same principle: the extraction already ran for the prompt, so the evaluator reuses it
 * instead of paying for a second pass. Empty when no document was examined (structured-data Fast
 * Track, or an early missing-documentation exit) — {@code DocumentInconsistencyEvaluator} reads
 * that as "not evaluable", never as "nothing was wrong".
 *
 * <p>{@code fraudRecords} is the one piece that isn't about this claim: what the insurer already
 * determined about this <b>person</b> on earlier files. It's read locally (the table lives in this
 * module) rather than fetched from cases-service, so it costs a query and not a network hop. Empty
 * means the insured has no record — never "we didn't look".
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

    /** No image analysis and no documents examined (isolated flows, or when neither ran). */
    public RiskContext(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        this(claim, policy, history, rules, null, Map.of(), List.of());
    }

    public RiskContext(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules,
                       ImageForensicReport imageFraud, Map<String, DocumentExtraction> documents) {
        this(claim, policy, history, rules, imageFraud, documents, List.of());
    }
}
