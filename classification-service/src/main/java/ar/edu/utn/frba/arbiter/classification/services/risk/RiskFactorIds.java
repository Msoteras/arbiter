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

    private RiskFactorIds() {}
}
