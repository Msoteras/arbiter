package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRecommendationEvaluatorTest {

    private final LlmRecommendationEvaluator evaluator = new LlmRecommendationEvaluator();

    private static RiskContext contextWith(Classification recommendation) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                RiskFixtures.rules(null),
                null, Map.of(), List.of(), recommendation);
    }

    @Test
    void recommendingApprovalAddsNoRisk() {
        Contribution contribution = evaluator.evaluate(contextWith(Classification.LLM_RECOMIENDA_APROBAR));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isZero();
        assertThat(contribution.rationale()).isEqualTo("El modelo recomienda aprobar");
    }

    @Test
    void askingForManualReviewAddsHalfTheRisk() {
        Contribution contribution = evaluator.evaluate(contextWith(Classification.LLM_SOLICITA_REVISION_MANUAL));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isEqualTo(0.5);
    }

    @Test
    void notRecommendingApprovalAddsFullRisk() {
        Contribution contribution = evaluator.evaluate(contextWith(Classification.LLM_NO_RECOMIENDA_APROBAR));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isEqualTo(1.0);
    }

    @Test
    void isNotEvaluableWhenTheModelDidNotRun() {
        assertThat(evaluator.evaluate(contextWith(null)).evaluable()).isFalse();
    }
}
