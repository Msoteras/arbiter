package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PurchaseToReportTimeEvaluatorTest {

    private static final LocalDate EVENT_DAY = RiskFixtures.EVENT_DATE.toLocalDate();

    private final PurchaseToReportTimeEvaluator evaluator = new PurchaseToReportTimeEvaluator();

    private RiskContext context(Map<String, DocumentExtraction> documents) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000"), EVENT_DAY.minusDays(3)),
                RiskFixtures.history(0),
                RiskFixtures.rules(null),
                null,
                documents);
    }

    private Map<String, DocumentExtraction> purchaseProofDated(LocalDate date) {
        return Map.of("purchase_proof", dated(date));
    }

    private DocumentExtraction dated(LocalDate date) {
        return new DocumentExtraction("texto de la factura", List.of(), new DocumentExtraction.Fields(
                date, null, null, null, null, null, null, null, List.of()));
    }

    @Test
    void claimRightAfterPurchaseIsMaxRisk() {
        Contribution c = evaluator.evaluate(context(purchaseProofDated(EVENT_DAY.minusDays(3))));

        assertThat(c.score()).isEqualTo(1.0);
    }

    @Test
    void longTenureBeforeClaimHasNoRisk() {
        Contribution c = evaluator.evaluate(context(purchaseProofDated(EVENT_DAY.minusDays(200))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    @Test
    void decaysLinearlyBetweenSuspiciousAndSafe() {
        // 30 days before the event -> (90-30)/(90-7)
        Contribution c = evaluator.evaluate(context(purchaseProofDated(EVENT_DAY.minusDays(30))));

        assertThat(c.score()).isCloseTo(60.0 / 83.0, within(1e-9));
    }

    /** The policy in the context started 3 days before the event: it must not stand in for the purchase. */
    @Test
    void withoutPurchaseProofIsNotEvaluableEvenWithARecentPolicy() {
        Contribution c = evaluator.evaluate(context(Map.of()));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void onlyThePurchaseProofDateCounts() {
        Contribution c = evaluator.evaluate(context(Map.of("police_report", dated(EVENT_DAY))));

        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void purchaseProofWithoutDateIsNotEvaluable() {
        Contribution c = evaluator.evaluate(context(purchaseProofDated(null)));

        assertThat(c.rationale()).contains("no evaluable");
    }

    @Test
    void purchaseAfterTheEventIsNotEvaluable() {
        Contribution c = evaluator.evaluate(context(purchaseProofDated(EVENT_DAY.plusDays(5))));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("no evaluable");
    }
}
