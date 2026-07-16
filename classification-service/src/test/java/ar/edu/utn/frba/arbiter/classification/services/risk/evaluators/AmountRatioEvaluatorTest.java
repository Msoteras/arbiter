package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AmountRatioEvaluatorTest {

    private final AmountRatioEvaluator evaluator = new AmountRatioEvaluator();

    private RiskContext contextWith(BigDecimal claimed, BigDecimal insured) {
        return new RiskContext(
                RiskFixtures.claim(claimed),
                RiskFixtures.policy(true, insured),
                RiskFixtures.history(0),
                RiskFixtures.rules(null));
    }

    @Test
    void ratioMapsDirectlyToScore() {
        Contribution c = evaluator.evaluate(contextWith(new BigDecimal("200000"), new BigDecimal("400000")));

        assertThat(c.score()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void claimAboveInsuredAmountClampsToOne() {
        Contribution c = evaluator.evaluate(contextWith(new BigDecimal("500000"), new BigDecimal("400000")));

        assertThat(c.score()).isEqualTo(1.0);
    }

    @Test
    void missingClaimedAmountIsNotEvaluable() {
        Contribution c = evaluator.evaluate(contextWith(null, new BigDecimal("400000")));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void zeroInsuredAmountIsNotEvaluable() {
        Contribution c = evaluator.evaluate(contextWith(new BigDecimal("200000"), BigDecimal.ZERO));

        assertThat(c.score()).isEqualTo(0.0);
    }
}
