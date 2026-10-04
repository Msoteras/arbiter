package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

/**
 * Full risk up to {@link #SUSPICIOUS_DAYS} between the purchase and the event, decaying linearly to
 * zero at {@link #SAFE_DAYS}. The purchase date is the one on the purchase proof; the policy start is
 * not a fallback, since a claim right after it is already covered by the waiting period rule.
 */
@Component
public class PurchaseToReportTimeEvaluator implements RiskFactorEvaluator {

    static final long SUSPICIOUS_DAYS = 7;
    static final long SAFE_DAYS = 90;

    private static final String PURCHASE_PROOF_TYPE = "purchase_proof";

    @Override
    public String factorId() {
        return RiskFactorIds.PURCHASE_TO_REPORT_TIME;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        DocumentExtraction purchaseProof = context.documents().get(PURCHASE_PROOF_TYPE);
        var purchaseDate = purchaseProof == null ? null : purchaseProof.fields().documentDate();
        var eventDate = context.claim() == null ? null : context.claim().eventDate();
        if (purchaseDate == null || eventDate == null) {
            return Contribution.notEvaluable(factorId(),
                    "Sin fecha de compra en la factura o sin fecha del hecho — factor no evaluable");
        }

        long days = ChronoUnit.DAYS.between(purchaseDate, eventDate.toLocalDate());
        if (days < 0) {
            return Contribution.notEvaluable(factorId(),
                    "La factura es posterior al hecho — factor no evaluable");
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
                String.format("Transcurrieron %d días entre la compra y el hecho denunciado", days));
    }
}
