package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import org.springframework.stereotype.Component;

/** Risk = the strongest internal match's similarity. */
@Component
public class ImageReuseEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.IMAGE_REUSE;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        ImageForensicReport fraud = context.imageFraud();
        if (fraud == null || fraud.imagesAnalyzed() == 0) {
            return Contribution.notEvaluable(factorId(),
                    "Sin análisis de imágenes disponible — factor no evaluable");
        }

        double maxSimilarity = fraud.findings().stream()
                .flatMap(f -> f.internalMatches().stream())
                .mapToDouble(ImageForensicReport.InternalMatch::similarity)
                .max()
                .orElse(0.0);

        if (maxSimilarity <= 0.0) {
            // Analyzed with no match: a real, evaluable 0.
            return new Contribution(factorId(), 0.0,
                    "Ninguna imagen coincide con adjuntos de siniestros previos");
        }

        double score = Math.min(1.0, maxSimilarity);
        return new Contribution(factorId(), score, String.format(
                "Una imagen es %.0f%% similar a un adjunto de un siniestro previo — posible reutilización", score * 100));
    }
}
