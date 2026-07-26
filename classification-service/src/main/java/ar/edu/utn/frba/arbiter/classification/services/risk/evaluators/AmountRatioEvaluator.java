package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * How large the claimed amount is relative to the insured sum. A claim close to (or above) the
 * full insured amount is riskier than a small one. Risk = clamp(claimed / insured, 0, 1).
 */
@Component
public class AmountRatioEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.AMOUNT_RATIO;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        BigDecimal claimed = context.claim().claimedAmount();
        BigDecimal insured = context.policy().insuredAmount();

        if (claimed == null || insured == null || insured.signum() <= 0) {
            return Contribution.notEvaluable(factorId(),
                    "Monto reclamado o suma asegurada no disponible — factor no evaluable");
        }

        double ratio = claimed.doubleValue() / insured.doubleValue();
        double score = Math.min(1.0, Math.max(0.0, ratio));
        return new Contribution(factorId(), score,
                String.format("Monto reclamado es %.0f%% de la suma asegurada", ratio * 100));
    }
}
