package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PurchaseToReportTimeEvaluatorTest {

    private final PurchaseToReportTimeEvaluator evaluator = new PurchaseToReportTimeEvaluator();

    /** Event date is fixed at {@link RiskFixtures#EVENT_DATE}; each test moves the policy start. */
    private RiskContext contextWithPolicyStart(LocalDate effectiveFrom) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000"), effectiveFrom),
                RiskFixtures.history(0),
                RiskFixtures.rules(null));
    }

    @Test
    void claimRightAfterPurchaseIsMaxRisk() {
        // 3 days between policy start and the event (<= SUSPICIOUS_DAYS)
        Contribution c = evaluator.evaluate(contextWithPolicyStart(RiskFixtures.EVENT_DATE.toLocalDate().minusDays(3)));

        assertThat(c.score()).isEqualTo(1.0);
    }

    @Test
    void longTenureBeforeClaimHasNoRisk() {
        // 163 days before the event (>= SAFE_DAYS)
        Contribution c = evaluator.evaluate(contextWithPolicyStart(LocalDate.of(2026, 1, 1)));

        assertThat(c.score()).isEqualTo(0.0);
    }

    @Test
    void decaysLinearlyBetweenSuspiciousAndSafe() {
        // 30 days before the event -> (90-30)/(90-7)
        Contribution c = evaluator.evaluate(contextWithPolicyStart(RiskFixtures.EVENT_DATE.toLocalDate().minusDays(30)));

        assertThat(c.score()).isCloseTo(60.0 / 83.0, within(1e-9));
    }

    @Test
    void eventBeforePolicyStartIsNotEvaluable() {
        Contribution c = evaluator.evaluate(contextWithPolicyStart(RiskFixtures.EVENT_DATE.toLocalDate().plusDays(5)));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void missingDatesAreNotEvaluable() {
        Contribution c = evaluator.evaluate(contextWithPolicyStart(null));

        assertThat(c.score()).isEqualTo(0.0);
    }
}
