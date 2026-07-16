package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

/**
 * How often the insured has claimed before. A repeat claimant is riskier than a first-timer.
 * Risk ramps linearly with prior claims and saturates at {@link #MAX_EXPECTED_CLAIMS}.
 */
@Component
public class ClaimFrequencyEvaluator implements RiskFactorEvaluator {

    /** Prior-claim count at which this factor reaches maximum risk. */
    static final double MAX_EXPECTED_CLAIMS = 3.0;

    @Override
    public String factorId() {
        return RiskFactorIds.CLAIM_FREQUENCY;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        int priorClaims = Math.max(0, context.history().previousClaimsCount());
        double score = Math.min(1.0, priorClaims / MAX_EXPECTED_CLAIMS);
        return new Contribution(factorId(), score,
                String.format("Siniestros previos del asegurado: %d", priorClaims));
    }
}
