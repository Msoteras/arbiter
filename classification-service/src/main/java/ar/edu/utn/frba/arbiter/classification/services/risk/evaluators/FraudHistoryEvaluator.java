package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Whether the insured already has a verified fraud behind them. This is the factor that closes the
 * loop {@code ClaimFrequencyEvaluator} leaves open: that one counts how many times someone claimed
 * and is blind to how those claims ended, so three settled claims and three frauds grade the same.
 *
 * <p>Only records that are <b>expert-backed and in force</b> count. An {@code ANALYST_DECLARED}
 * record is an alert for whoever reviews the claim, never a number: a suspicion that moves the
 * score raises the next claim's score, which raises the one after that, and the person has no way
 * out of a loop that feeds on itself. In-force is decided by the insurer's window — an old fraud
 * stops counting on its own, without anyone having to remember to clear it.
 *
 * <p>Not evaluable when the insurer has the rule off: with no configured window there's no
 * defensible answer to "does a 2019 record still count", and the factor drops out of the weighted
 * average rather than contributing a 0.0 that would read as "this person has a clean record".
 *
 * <p>One in-force record is already the maximum. A second one says the same thing the first did —
 * that this insured has defrauded and it was verified — and grading "twice" above "once" would put
 * the engine in the business of ranking people, which is not what the score is for.
 */
@Component
public class FraudHistoryEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.FRAUD_HISTORY;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        BusinessRules.FraudRecordPolicy policy = context.rules() == null
                ? null
                : context.rules().fraudRecordPolicy();
        if (policy == null || !policy.enabled()) {
            return Contribution.notEvaluable(factorId(),
                    "La aseguradora no tiene configurada la política de antecedentes de fraude");
        }

        LocalDate today = LocalDate.now();
        List<InsuredFraudRecord> counting = context.fraudRecords().stream()
                .filter(record -> record.counts(policy.windowMonths(), today))
                .toList();

        if (counting.isEmpty()) {
            return new Contribution(factorId(), 0.0,
                    "Sin antecedentes de fraude con respaldo pericial vigentes (ventana: "
                            + policy.windowMonths() + " meses)");
        }

        // Explicitly the newest rather than the first row: the rationale names a case file the
        // analyst may go read, and it shouldn't depend on how the caller happened to sort the list.
        InsuredFraudRecord mostRecent = counting.stream()
                .max(Comparator.comparing(InsuredFraudRecord::getDeclaredAt))
                .orElseThrow();
        return new Contribution(factorId(), 1.0,
                String.format("Antecedente de fraude con respaldo pericial del expediente %d, vigente (ventana: %d meses)",
                        mostRecent.getCaseId(), policy.windowMonths()));
    }
}
