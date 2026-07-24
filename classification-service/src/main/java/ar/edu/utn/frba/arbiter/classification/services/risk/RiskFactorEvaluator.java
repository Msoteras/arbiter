package ar.edu.utn.frba.arbiter.classification.services.risk;

/**
 * A single graded risk signal. Each evaluator reads the {@link RiskContext} and returns a
 * normalized contribution in [0.0, 1.0] (0 = no risk, 1 = maximum risk) plus a human-readable
 * rationale for the analyst's audit trail. Evaluators are stateless and never decide anything on
 * their own — {@link RiskScoringService} weighs and combines them per the insurer's config.
 */
public interface RiskFactorEvaluator {

    /** Id this evaluator answers to, matched against the insurer's active factors. See {@link RiskFactorIds}. */
    String factorId();

    Contribution evaluate(RiskContext context);

    /**
     * @param score normalized risk in [0.0, 1.0]
     * @param rationale why this score, in plain language for the analyst
     */
    record Contribution(String factorId, double score, String rationale) {

        public Contribution {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Risk contribution for '" + factorId + "' out of range [0,1]: " + score);
            }
        }
    }
}
