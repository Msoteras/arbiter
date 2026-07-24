package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentInconsistencyEvaluatorTest {

    private final DocumentInconsistencyEvaluator evaluator = new DocumentInconsistencyEvaluator();

    @Test
    void stubIsNeutralAndAdvertisesItself() {
        RiskContext context = new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                RiskFixtures.rules(null));

        Contribution c = evaluator.evaluate(context);

        assertThat(c.factorId()).isEqualTo(RiskFactorIds.DOCUMENT_INCONSISTENCY);
        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).containsIgnoringCase("stub");
    }
}
