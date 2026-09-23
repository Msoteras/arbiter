package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

@Component
public class PolicyStandingEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.POLICY_STANDING;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        boolean upToDate = context.policy().upToDate();
        return upToDate
                ? new Contribution(factorId(), 0.0, "Póliza al día con sus pagos")
                : new Contribution(factorId(), 1.0, "Póliza con pagos atrasados al momento del siniestro");
    }
}
