package ar.edu.utn.frba.arbiter.classification.services.risk;

/** Contract between the insurer's scoring config (stored in DB) and the evaluators: don't rename. */
public final class RiskFactorIds {

    public static final String AMOUNT_RATIO = "amount_ratio";
    public static final String CLAIM_FREQUENCY = "claim_frequency";
    public static final String POLICY_STANDING = "policy_standing";
    public static final String PURCHASE_TO_REPORT_TIME = "purchase_to_report_time";
    public static final String DOCUMENT_INCONSISTENCY = "document_inconsistency";
    public static final String IMAGE_REUSE = "image_reuse";
    public static final String IMAGE_WEB_MATCH = "image_web_match";
    public static final String FRAUD_HISTORY = "fraud_history";

    private RiskFactorIds() {}
}
