package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRecordRuleEvaluatorTest {

    private static final int WINDOW_MONTHS = 60;

    private final FraudRecordRuleEvaluator evaluator = new FraudRecordRuleEvaluator();

    /** Sin fila no hay nada que evaluar, ni a qué apuntar desde rule_result. */
    @Test
    void withNoRuleConfiguredNothingIsEvaluated() {
        FraudRecordRuleEvaluator.Result result = evaluator.evaluate(
                rulesWith(BusinessRules.FraudRecordPolicy.unconfigured()), expertBacked(monthsAgo(1)));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).isEmpty();
        assertThat(result.reasons()).isEmpty();
    }

    /** PASS is written too: a table with only failures doesn't prove the rule was evaluated. */
    @Test
    void cleanInsuredLeavesAPassFinding() {
        FraudRecordRuleEvaluator.Result result = evaluator.evaluate(rulesWith(policy(true)), List.of());

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.passed()).isTrue();
                    assertThat(finding.ruleId()).isEqualTo(17L);
                    assertThat(finding.ruleType()).isEqualTo(RuleType.FRAUD_RECORD.name());
                });
    }

    @Test
    void inForceExpertRecordBlocksFastTrackAndFails() {
        FraudRecordRuleEvaluator.Result result =
                evaluator.evaluate(rulesWith(policy(true)), expertBacked(monthsAgo(12)));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).isNotEmpty();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.passed()).isFalse());
    }

    /**
     * The insurer that leaves the veto off still gets the finding and the analyst still gets the
     * reason — what it doesn't get is the claim losing Fast Track over it.
     */
    @Test
    void vetoOffStillReportsTheRecordWithoutBlocking() {
        FraudRecordRuleEvaluator.Result result =
                evaluator.evaluate(rulesWith(policy(false)), expertBacked(monthsAgo(12)));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.reasons()).isNotEmpty();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.passed()).isFalse());
    }

    @Test
    void analystDeclaredRecordNeverBlocks() {
        FraudRecordRuleEvaluator.Result result = evaluator.evaluate(
                rulesWith(policy(true)),
                List.of(record(FraudRecordSource.ANALYST_DECLARED, monthsAgo(1))));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).extracting(RuleFinding::passed).containsExactly(true);
    }

    @Test
    void recordPastTheWindowNoLongerBlocks() {
        FraudRecordRuleEvaluator.Result result = evaluator.evaluate(
                rulesWith(policy(true)), expertBacked(monthsAgo(WINDOW_MONTHS + 1)));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).extracting(RuleFinding::passed).containsExactly(true);
    }

    private BusinessRules rulesWith(BusinessRules.FraudRecordPolicy policy) {
        return RiskFixtures.rules(null).toBuilder().fraudRecordPolicy(policy).build();
    }

    private BusinessRules.FraudRecordPolicy policy(boolean blocksFastTrack) {
        return BusinessRules.FraudRecordPolicy.builder()
                .ruleId(17L).windowMonths(WINDOW_MONTHS).blocksFastTrack(blocksFastTrack).build();
    }

    private List<InsuredFraudRecord> expertBacked(Instant declaredAt) {
        return List.of(record(FraudRecordSource.EXPERT_BACKED, declaredAt));
    }

    private InsuredFraudRecord record(FraudRecordSource source, Instant declaredAt) {
        InsuredFraudRecord record = InsuredFraudRecord.builder()
                .insuredDni("40.123.456")
                .caseId(77L)
                .source(source)
                .reason("El peritaje verificó que el equipo denunciado nunca existió")
                .declaredByAnalystId(1L)
                .declaredByAnalystName("Ana Gómez")
                .build();
        record.setDeclaredAt(declaredAt);
        return record;
    }

    private Instant monthsAgo(int months) {
        return Instant.now().minus(31L * months, ChronoUnit.DAYS);
    }
}
