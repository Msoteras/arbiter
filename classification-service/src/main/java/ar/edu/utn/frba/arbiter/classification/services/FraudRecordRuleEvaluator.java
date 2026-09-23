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
 * An expert-backed fraud record inside the insurer's window can veto Fast Track, if the insurer
 * turned the veto on. It never rejects or classifies: it only makes a person look. Always leaves an
 * auditable {@link RuleFinding}.
 */
@Service
public class FraudRecordRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FraudRecordRuleEvaluator.class);

    public record Result(boolean blocksFastTrack, List<String> reasons, List<RuleFinding> findings) {

        public static Result empty() {
            return new Result(false, List.of(), List.of());
        }
    }

    public Result evaluate(BusinessRules rules, List<InsuredFraudRecord> fraudRecords) {
        BusinessRules.FraudRecordPolicy policy = rules == null ? null : rules.fraudRecordPolicy();
        // Without a rule row there's nothing for rule_result's NOT NULL rule_id to point at.
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
        // With the veto off the FAIL row and the reason are still recorded; only Fast Track is kept.
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
