package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudHistoryEvaluatorTest {

    private static final int WINDOW_MONTHS = 60;

    private final FraudHistoryEvaluator evaluator = new FraudHistoryEvaluator();

    @Test
    void notEvaluableWhenTheInsurerHasNoFraudRecordPolicy() {
        Contribution contribution = evaluator.evaluate(context(disabledPolicy(), expertBacked(monthsAgo(1))));

        assertThat(contribution.evaluable()).isFalse();
        assertThat(contribution.rationale()).contains("no tiene configurada");
    }

    @Test
    void noRecordsScoresZero() {
        Contribution contribution = evaluator.evaluate(context(activePolicy(), List.of()));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isEqualTo(0.0);
    }

    @Test
    void expertBackedRecordInsideTheWindowScoresMaximum() {
        Contribution contribution = evaluator.evaluate(context(activePolicy(), expertBacked(monthsAgo(12))));

        assertThat(contribution.score()).isEqualTo(1.0);
        assertThat(contribution.rationale()).contains("expediente 77");
    }

    /** The window is the whole point: an old fraud stops counting without anyone clearing it. */
    @Test
    void expertBackedRecordPastTheWindowStopsCounting() {
        Contribution contribution = evaluator.evaluate(
                context(activePolicy(), expertBacked(monthsAgo(WINDOW_MONTHS + 1))));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isEqualTo(0.0);
    }

    /**
     * The decision this factor exists to protect: a suspicion the analyst wrote down never moves
     * the score, or the score of today would be feeding the score of tomorrow.
     */
    @Test
    void analystDeclaredRecordNeverScores() {
        Contribution contribution = evaluator.evaluate(
                context(activePolicy(), analystDeclared(monthsAgo(1))));

        assertThat(contribution.evaluable()).isTrue();
        assertThat(contribution.score()).isEqualTo(0.0);
    }

    /** Two verified frauds say what one already said; the engine doesn't rank people. */
    @Test
    void severalRecordsDoNotStack() {
        Contribution one = evaluator.evaluate(context(activePolicy(), expertBacked(monthsAgo(12))));
        List<InsuredFraudRecord> many = List.of(
                record(FraudRecordSource.EXPERT_BACKED, monthsAgo(12), 77L),
                record(FraudRecordSource.EXPERT_BACKED, monthsAgo(30), 55L));

        assertThat(evaluator.evaluate(context(activePolicy(), many)).score()).isEqualTo(one.score());
    }

    @Test
    void theRationaleNamesTheMostRecentCaseRegardlessOfListOrder() {
        List<InsuredFraudRecord> oldestFirst = List.of(
                record(FraudRecordSource.EXPERT_BACKED, monthsAgo(30), 55L),
                record(FraudRecordSource.EXPERT_BACKED, monthsAgo(2), 91L));

        assertThat(evaluator.evaluate(context(activePolicy(), oldestFirst)).rationale())
                .contains("expediente 91");
    }

    private RiskContext context(BusinessRules.FraudRecordPolicy policy, List<InsuredFraudRecord> records) {
        BusinessRules rules = RiskFixtures.rules(null).toBuilder().fraudRecordPolicy(policy).build();
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                RiskFixtures.policy(true, new BigDecimal("400000")),
                RiskFixtures.history(0),
                rules,
                null,
                null,
                records);
    }

    private BusinessRules.FraudRecordPolicy activePolicy() {
        return BusinessRules.FraudRecordPolicy.builder()
                .ruleId(17L).enabled(true).windowMonths(WINDOW_MONTHS).blocksFastTrack(true).build();
    }

    private BusinessRules.FraudRecordPolicy disabledPolicy() {
        return BusinessRules.FraudRecordPolicy.disabled();
    }

    private List<InsuredFraudRecord> expertBacked(Instant declaredAt) {
        return List.of(record(FraudRecordSource.EXPERT_BACKED, declaredAt, 77L));
    }

    private List<InsuredFraudRecord> analystDeclared(Instant declaredAt) {
        return List.of(record(FraudRecordSource.ANALYST_DECLARED, declaredAt, 77L));
    }

    private InsuredFraudRecord record(FraudRecordSource source, Instant declaredAt, Long caseId) {
        InsuredFraudRecord record = InsuredFraudRecord.builder()
                .insuredDni("40.123.456")
                .caseId(caseId)
                .source(source)
                .reason("El peritaje verificó que el equipo denunciado nunca existió")
                .declaredByAnalystId(1L)
                .declaredByAnalystName("Ana Gómez")
                .build();
        record.setDeclaredAt(declaredAt);
        return record;
    }

    private Instant monthsAgo(int months) {
        // 31 días por mes: se aleja del borde en vez de acercarse, así el caso "vencido" queda
        // vencido y el "vigente" vigente sin que el test dependa de qué día se corre.
        return Instant.now().minus(31L * months, ChronoUnit.DAYS);
    }
}
