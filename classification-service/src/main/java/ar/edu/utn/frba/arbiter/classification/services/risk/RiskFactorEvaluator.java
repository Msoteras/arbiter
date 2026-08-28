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
     * @param score      normalized risk in [0.0, 1.0]
     * @param rationale  why this score, in plain language for the analyst
     * @param evaluable  whether the factor could actually be evaluated. {@code false} means the
     *                   data to judge it wasn't there (missing amounts, no image analysis, …) —
     *                   distinct from an evaluated 0.0 (genuinely no risk, e.g. an up-to-date
     *                   policy). {@link RiskScoringService} drops non-evaluable factors from the
     *                   weighted average entirely, so they don't dilute the score of claims the
     *                   factor doesn't apply to.
     */
    record Contribution(String factorId, double score, String rationale, boolean evaluable) {

        public Contribution {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Risk contribution for '" + factorId + "' out of range [0,1]: " + score);
            }
        }

        /** An evaluated contribution (the common case): the factor applied and produced a score. */
        public Contribution(String factorId, double score, String rationale) {
            this(factorId, score, rationale, true);
        }

        /** The factor couldn't be evaluated (missing data) — excluded from the weighted average. */
        public static Contribution notEvaluable(String factorId, String rationale) {
            return new Contribution(factorId, 0.0, rationale, false);
        }
    }
}
