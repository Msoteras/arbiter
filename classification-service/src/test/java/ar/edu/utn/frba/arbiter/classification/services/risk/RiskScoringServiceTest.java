package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules.ScoringConfig;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.services.risk.evaluators.AmountRatioEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.evaluators.ClaimFrequencyEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.evaluators.DocumentInconsistencyEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.evaluators.PolicyStandingEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.evaluators.PurchaseToReportTimeEvaluator;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures.band;
import static ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures.factor;
import static ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures.gaugeBands;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskScoringServiceTest {

    private final RiskScoringService service = new RiskScoringService(List.of(
            new AmountRatioEvaluator(),
            new ClaimFrequencyEvaluator(),
            new PolicyStandingEvaluator(),
            new PurchaseToReportTimeEvaluator(),
            new DocumentInconsistencyEvaluator()));

    @Test
    void weightedSumIsNormalizedByTotalWeightAndBanded() {
        // ratio 0.5 (weight 0.4) + policy up to date -> 0.0 (weight 0.6)
        ScoringConfig config = ScoringConfig.builder()
                .factors(List.of(
                        factor(RiskFactorIds.AMOUNT_RATIO, 0.4),
                        factor(RiskFactorIds.POLICY_STANDING, 0.6)))
                .bands(gaugeBands())
                .build();
        RiskContext ctx = RiskFixtures.context(
                RiskFixtures.claim(new BigDecimal("200000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                config);

        RiskScore result = service.score(ctx);

        // (0.5*0.4 + 0.0*0.6) / (0.4+0.6) = 0.2
        assertThat(result.score()).isCloseTo(0.2, within(1e-9));
        assertThat(result.band()).isEqualTo(RiskBand.LOW);
        assertThat(result.breakdown()).hasSize(2);
        assertThat(result.breakdown())
                .filteredOn(b -> b.factorId().equals(RiskFactorIds.AMOUNT_RATIO))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.rawScore()).isCloseTo(0.5, within(1e-9));
                    assertThat(b.weight()).isEqualTo(0.4);
                    assertThat(b.weightedContribution()).isCloseTo(0.2, within(1e-9));
                });
    }

    @Test
    void normalizationIsIndependentOfWeightScale() {
        // single factor with a non-unit weight must still yield the raw contribution
        ScoringConfig config = ScoringConfig.builder()
                .factors(List.of(factor(RiskFactorIds.AMOUNT_RATIO, 3.0)))
                .bands(gaugeBands())
                .build();
        RiskContext ctx = RiskFixtures.context(
                RiskFixtures.claim(new BigDecimal("360000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                config);

        RiskScore result = service.score(ctx);

        assertThat(result.score()).isCloseTo(0.9, within(1e-9));
        assertThat(result.band()).isEqualTo(RiskBand.CRITICAL);
    }

    @Test
    void noScoringConfigYieldsNeutralScore() {
        RiskContext ctx = RiskFixtures.context(
                RiskFixtures.claim(new BigDecimal("360000")),
                RiskFixtures.policy(false, new BigDecimal("400000")),
                RiskFixtures.history(4),
                null);

        RiskScore result = service.score(ctx);

        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.band()).isEqualTo(RiskBand.LOW);
        assertThat(result.breakdown()).isEmpty();
    }

    @Test
    void activeFactorWithoutEvaluatorIsSkipped() {
        ScoringConfig config = ScoringConfig.builder()
                .factors(List.of(
                        factor("unknown_factor", 1.0),
                        factor(RiskFactorIds.AMOUNT_RATIO, 1.0)))
                .bands(gaugeBands())
                .build();
        RiskContext ctx = RiskFixtures.context(
                RiskFixtures.claim(new BigDecimal("200000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                config);

        RiskScore result = service.score(ctx);

        assertThat(result.breakdown()).hasSize(1);
        assertThat(result.score()).isCloseTo(0.5, within(1e-9));
    }

    /**
     * Genericity proof: the SAME claim/policy/history scored under two different insurer configs
     * (different weights AND different bands) must produce different scores and different bands.
     */
    @Test
    void sameClaimUnderTwoInsurerConfigsGivesDifferentScoreAndBand() {
        ClaimReport claim = RiskFixtures.claim(new BigDecimal("360000"));   // ratio 0.9
        InsuredPolicy policy = RiskFixtures.policy(true, new BigDecimal("400000")); // standing 0.0
        InsuredHistory history = RiskFixtures.history(1);                   // frequency 0.2

        // Insurer A weighs the claimed amount heavily, standard gauge bands.
        ScoringConfig insurerA = ScoringConfig.builder()
                .factors(List.of(
                        factor(RiskFactorIds.AMOUNT_RATIO, 0.7),
                        factor(RiskFactorIds.CLAIM_FREQUENCY, 0.2),
                        factor(RiskFactorIds.POLICY_STANDING, 0.1)))
                .bands(gaugeBands())
                .build();

        // Insurer B barely weighs the amount, leans on standing/frequency, and uses stricter bands.
        ScoringConfig insurerB = ScoringConfig.builder()
                .factors(List.of(
                        factor(RiskFactorIds.AMOUNT_RATIO, 0.2),
                        factor(RiskFactorIds.CLAIM_FREQUENCY, 0.3),
                        factor(RiskFactorIds.POLICY_STANDING, 0.5)))
                .bands(List.of(
                        band(RiskBand.LOW, 0.00),
                        band(RiskBand.MEDIUM, 0.20),
                        band(RiskBand.HIGH, 0.40),
                        band(RiskBand.CRITICAL, 0.60)))
                .build();

        RiskScore scoreA = service.score(RiskFixtures.context(claim, policy, history, insurerA));
        RiskScore scoreB = service.score(RiskFixtures.context(claim, policy, history, insurerB));

        // A: 0.9*0.7 + 0.2*0.2 + 0.0*0.1 = 0.67 -> HIGH
        assertThat(scoreA.score()).isCloseTo(0.67, within(1e-9));
        assertThat(scoreA.band()).isEqualTo(RiskBand.HIGH);

        // B: 0.9*0.2 + 0.2*0.3 + 0.0*0.5 = 0.24 -> MEDIUM (under B's stricter bands)
        assertThat(scoreB.score()).isCloseTo(0.24, within(1e-9));
        assertThat(scoreB.band()).isEqualTo(RiskBand.MEDIUM);

        assertThat(scoreA.score()).isNotEqualTo(scoreB.score());
        assertThat(scoreA.band()).isNotEqualTo(scoreB.band());
    }
}
