package ar.edu.utn.frba.arbiter.classification.services.risk;

/** One risk signal in [0.0, 1.0] with a rationale for the analyst; {@link RiskScoringService} weighs them. */
public interface RiskFactorEvaluator {

    String factorId();

    Contribution evaluate(RiskContext context);

    /**
     * @param evaluable {@code false} when the data to judge it was missing, unlike an evaluated 0.0;
     *                  non-evaluable factors are dropped from the weighted average so they don't dilute it
     */
    record Contribution(String factorId, double score, String rationale, boolean evaluable) {

        public Contribution {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Risk contribution for '" + factorId + "' out of range [0,1]: " + score);
            }
        }

        public Contribution(String factorId, double score, String rationale) {
            this(factorId, score, rationale, true);
        }

        public static Contribution notEvaluable(String factorId, String rationale) {
            return new Contribution(factorId, 0.0, rationale, false);
        }
    }
}
