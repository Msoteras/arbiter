package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

/**
 * Time between buying the policy and reporting the claim. A claim filed right after the policy
 * became effective is a classic fraud signal; the longer the gap, the lower the risk.
 *
 * <p>Milestone-1 heuristic over the data at hand ({@code policy.effectiveFrom} as a proxy for the
 * purchase date, {@code claim.eventDate} for the event): full risk at/under {@link #SUSPICIOUS_DAYS}
 * days, decaying linearly to zero at {@link #SAFE_DAYS}. The proxy (effectiveFrom ≈ purchase) is the
 * stubbed part — it firms up once the real purchase date is available in the policy contract.
 */
@Component
public class PurchaseToReportTimeEvaluator implements RiskFactorEvaluator {

    static final long SUSPICIOUS_DAYS = 7;
    static final long SAFE_DAYS = 90;

    @Override
    public String factorId() {
        return RiskFactorIds.PURCHASE_TO_REPORT_TIME;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        var effectiveFrom = context.policy().effectiveFrom();
        var eventDate = context.claim().eventDate();
        if (effectiveFrom == null || eventDate == null) {
            return Contribution.notEvaluable(factorId(),
                    "Fechas de vigencia o del hecho no disponibles — factor no evaluable");
        }

        // Días, no horas: es un heurístico de riesgo, no necesita la precisión que sí le hace
        // falta a D13 (vigencia).
        long days = ChronoUnit.DAYS.between(effectiveFrom.toLocalDate(), eventDate.toLocalDate());
        if (days < 0) {
            return Contribution.notEvaluable(factorId(),
                    "Fecha del hecho anterior a la vigencia de la póliza — factor no evaluable");
        }

        double score;
        if (days <= SUSPICIOUS_DAYS) {
            score = 1.0;
        } else if (days >= SAFE_DAYS) {
            score = 0.0;
        } else {
            score = (double) (SAFE_DAYS - days) / (SAFE_DAYS - SUSPICIOUS_DAYS);
        }
        return new Contribution(factorId(), score,
                String.format("Transcurrieron %d días entre el alta de la póliza y el hecho denunciado", days));
    }
}
