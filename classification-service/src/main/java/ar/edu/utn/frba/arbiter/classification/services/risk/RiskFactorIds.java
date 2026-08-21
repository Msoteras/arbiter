package ar.edu.utn.frba.arbiter.classification.services.risk;

/**
 * Stable identifiers of the risk factors. They are the contract between an insurer's
 * scoring configuration ({@link ar.edu.utn.frba.arbiter.classification.dto.BusinessRules.ScoringConfig})
 * and the {@link RiskFactorEvaluator} beans: config activates a factor by id, the matching
 * evaluator computes its contribution. Kept as constants so config and evaluators can't drift.
 */
public final class RiskFactorIds {

    public static final String AMOUNT_RATIO = "amount_ratio";
    public static final String CLAIM_FREQUENCY = "claim_frequency";
    public static final String POLICY_STANDING = "policy_standing";
    public static final String PURCHASE_TO_REPORT_TIME = "purchase_to_report_time";
    public static final String DOCUMENT_INCONSISTENCY = "document_inconsistency";
    /** Image reused from another claim already in the system (internal pgvector match). */
    public static final String IMAGE_REUSE = "image_reuse";
    /** Claim image found already published on the web (stock/catalog/marketplace). */
    public static final String IMAGE_WEB_MATCH = "image_web_match";
    /** The insured has an expert-backed fraud record from an earlier claim, still in force. */
    public static final String FRAUD_HISTORY = "fraud_history";

    private RiskFactorIds() {}
}
