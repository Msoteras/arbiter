package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.springframework.stereotype.Component;

/**
 * The model's recommendation as a risk signal. Not evaluable when the rules settled the path: a
 * prescribed or excluded claim also carries an {@code LLM_*} value, but no model read it.
 */
@Component
public class LlmRecommendationEvaluator implements RiskFactorEvaluator {

    private static final double MANUAL_REVIEW_SCORE = 0.5;

    @Override
    public String factorId() {
        return RiskFactorIds.LLM_RECOMMENDATION;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        Classification recommendation = context.modelRecommendation();
        if (recommendation == Classification.LLM_RECOMIENDA_APROBAR) {
            return new Contribution(factorId(), 0.0, "El modelo recomienda aprobar");
        }
        if (recommendation == Classification.LLM_SOLICITA_REVISION_MANUAL) {
            return new Contribution(factorId(), MANUAL_REVIEW_SCORE, "El modelo pide revisión manual");
        }
        if (recommendation == Classification.LLM_NO_RECOMIENDA_APROBAR) {
            return new Contribution(factorId(), 1.0, "El modelo no recomienda aprobar");
        }
        return Contribution.notEvaluable(factorId(), "El modelo no analizó este expediente — factor no evaluable");
    }
}
