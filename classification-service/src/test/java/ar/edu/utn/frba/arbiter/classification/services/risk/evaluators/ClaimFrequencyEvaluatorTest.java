package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClaimFrequencyEvaluatorTest {

    private final ClaimFrequencyEvaluator evaluator = new ClaimFrequencyEvaluator();

    private RiskContext contextWith(int previousClaims) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(previousClaims),
                RiskFixtures.rules(null));
    }

    @Test
    void firstTimerHasNoRisk() {
        assertThat(evaluator.evaluate(contextWith(0)).score()).isEqualTo(0.0);
    }

    @Test
    void rampsLinearlyBelowSaturation() {
        assertThat(evaluator.evaluate(contextWith(1)).score()).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void saturatesAtMaxExpectedClaims() {
        assertThat(evaluator.evaluate(contextWith(5)).score()).isEqualTo(1.0);
    }

    @Test
    void clampsAboveSaturation() {
        assertThat(evaluator.evaluate(contextWith(12)).score()).isEqualTo(1.0);
    }
}
