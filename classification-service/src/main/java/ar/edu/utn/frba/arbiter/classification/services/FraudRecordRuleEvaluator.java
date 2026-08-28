package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * The hard rule over the insured's fraud records ({@code FRAUD_RECORD}): an expert-backed record
 * still inside the insurer's window disqualifies the claim from Fast Track.
 *
 * <p>It's the only hard rule that isn't about the claim in front of it, which is why it's the
 * insurer's call and not the engine's: {@code blocksFastTrack} travels on the rule row. Turning it
 * on says "someone whose fraud we verified doesn't get the expedited lane again"; leaving it off
 * says the record informs the analyst and the score, and nothing else.
 *
 * <p>It does not reject and it does not classify. A record about the person is not "una causa legal
 * o convencional de exclusión" for this claim — it's a reason to have a person look, which is what
 * losing Fast Track means (human-in-the-loop, decisión #5).
 *
 * <p>Like every hard rule, it leaves a {@link RuleFinding} — PASS and FAIL both — pointing at the
 * {@code insurer_rule} that was evaluated. A record that silently vetoed with nothing in
 * {@code rule_result} would be the exact opposite of what Disposición SSN 2/2023 asks for on the
 * decisions that weigh most.
 */
@Service
public class FraudRecordRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FraudRecordRuleEvaluator.class);

    /**
     * @param blocksFastTrack {@code true} if an in-force record disqualifies this claim
     * @param reasons         readable reasons for the analyst
     * @param findings        the {@code rule_result} rows to write (at most one: the rule is one row)
     */
    public record Result(boolean blocksFastTrack, List<String> reasons, List<RuleFinding> findings) {

        public static Result empty() {
            return new Result(false, List.of(), List.of());
        }
    }

    public Result evaluate(BusinessRules rules, List<InsuredFraudRecord> fraudRecords) {
        BusinessRules.FraudRecordPolicy policy = rules == null ? null : rules.fraudRecordPolicy();
        // Sin fila no hay nada que evaluar ni a qué apuntar desde rule_result (su FK es NOT NULL).
        if (policy == null || policy.ruleId() == null) {
            return Result.empty();
        }

        LocalDate today = LocalDate.now();
        List<InsuredFraudRecord> counting = fraudRecords.stream()
                .filter(record -> record.counts(policy.windowMonths(), today))
                .toList();

        if (counting.isEmpty()) {
            return new Result(false, List.of(), List.of(new RuleFinding(
                    policy.ruleId(), RuleType.FRAUD_RECORD.name(), true,
                    "sin antecedentes vigentes (ventana " + policy.windowMonths() + "m)")));
        }

        String evaluatedValue = "antecedente pericial vigente, expediente " + counting.getFirst().getCaseId();
        // The rule failing and the rule vetoing are separate: an insurer that leaves the veto off
        // still gets the FAIL row and the analyst still gets the reason — what it doesn't get is
        // the claim losing Fast Track over it.
        if (!policy.blocksFastTrack()) {
            log.info("[FraudRecordRule] In-force fraud record for the insured, veto off — Fast Track not blocked");
            return new Result(false,
                    List.of("El asegurado tiene un antecedente de fraude con respaldo pericial vigente"),
                    List.of(new RuleFinding(policy.ruleId(), RuleType.FRAUD_RECORD.name(), false, evaluatedValue)));
        }

        log.info("[FraudRecordRule] In-force fraud record for the insured — blocking Fast Track");
        return new Result(true,
                List.of("El asegurado tiene un antecedente de fraude con respaldo pericial vigente: "
                        + "el siniestro no puede resolverse por Fast Track"),
                List.of(new RuleFinding(policy.ruleId(), RuleType.FRAUD_RECORD.name(), false, evaluatedValue)));
    }
}
