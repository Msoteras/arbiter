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
 * Only expert-backed, in-force records count: an analyst-declared suspicion that moved the score
 * would feed on itself claim after claim. One record already scores the maximum; the engine doesn't
 * rank people by how many.
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
                || context.rules().fraudRecordPolicy() == null
                ? BusinessRules.FraudRecordPolicy.unconfigured()
                : context.rules().fraudRecordPolicy();

        LocalDate today = LocalDate.now();
        List<InsuredFraudRecord> counting = context.fraudRecords().stream()
                .filter(record -> record.counts(policy.windowMonths(), today))
                .toList();

        if (counting.isEmpty()) {
            return new Contribution(factorId(), 0.0,
                    "Sin antecedentes de fraude con respaldo pericial vigentes (ventana: "
                            + policy.windowMonths() + " meses)");
        }

        // The newest explicitly: the rationale names a case, so it mustn't depend on list order.
        InsuredFraudRecord mostRecent = counting.stream()
                .max(Comparator.comparing(InsuredFraudRecord::getDeclaredAt))
                .orElseThrow();
        return new Contribution(factorId(), 1.0,
                String.format("Antecedente de fraude con respaldo pericial del expediente %d, vigente (ventana: %d meses)",
                        mostRecent.getCaseId(), policy.windowMonths()));
    }
}
