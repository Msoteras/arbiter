package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyStandingEvaluatorTest {

    private final PolicyStandingEvaluator evaluator = new PolicyStandingEvaluator();

    private RiskContext contextWith(boolean upToDate) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(upToDate, new BigDecimal("400000")),
                RiskFixtures.history(0),
                RiskFixtures.rules(null));
    }

    @Test
    void policyUpToDateHasNoRisk() {
        Contribution c = evaluator.evaluate(contextWith(true));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("al día");
    }

    @Test
    void policyBehindOnPaymentsIsMaxRisk() {
        Contribution c = evaluator.evaluate(contextWith(false));

        assertThat(c.score()).isEqualTo(1.0);
        assertThat(c.rationale()).contains("atrasados");
    }
}
