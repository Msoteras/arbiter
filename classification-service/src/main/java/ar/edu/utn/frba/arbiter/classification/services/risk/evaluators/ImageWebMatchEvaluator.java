package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import org.springframework.stereotype.Component;

/**
 * A claim image found already published on the web (stock/catalog photo or a marketplace listing
 * passed off as the insured's damaged item). Graded from the PoC calibration:
 *
 * <ul>
 *   <li>an <b>exact</b> match is the strongest signal (a byte-level copy of a public image);</li>
 *   <li><b>partial</b> matches (cropped/resized) are weaker and noisier;</li>
 *   <li>appearing on <b>many pages</b> points to a catalog/stock image (the item may not even be
 *       the insured's) rather than the insured's own single listing.</li>
 * </ul>
 *
 * Only counts when the web search actually ran ({@code webFinding != null}); "not searched"
 * (external integration disabled) leaves the factor non-evaluable, never a false 0-risk signal.
 */
@Component
public class ImageWebMatchEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.IMAGE_WEB_MATCH;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        ImageForensicReport fraud = context.imageFraud();
        if (fraud == null || fraud.webSearchesPerformed() == 0) {
            return new Contribution(factorId(), 0.0,
                    "No se verificó contra internet — factor no evaluable, se asume sin aporte de riesgo");
        }

        double score = 0.0;
        WebFinding strongest = null;
        for (ImageForensicReport.ImageFinding f : fraud.findings()) {
            WebFinding web = f.webFinding();
            if (web == null || !web.found()) {
                continue;
            }
            double s = gradeWebFinding(web);
            if (s > score) {
                score = s;
                strongest = web;
            }
        }

        if (strongest == null) {
            return new Contribution(factorId(), 0.0,
                    "Ninguna imagen fue encontrada publicada en internet");
        }

        return new Contribution(factorId(), score, String.format(
                "Una imagen está publicada en internet (%d exacta(s), %d parcial(es), %d página(s)) — posible foto de catálogo/publicación",
                strongest.fullMatches(), strongest.partialMatches(), strongest.pages().size()));
    }

    /**
     * Exact matches dominate; partial matches and page spread add smaller, capped increments.
     * Weights favour precision (an exact hit alone already reads as high risk) while letting a
     * broad partial-match footprint push the score up without ever exceeding 1.
     */
    private double gradeWebFinding(WebFinding web) {
        double fromExact = web.fullMatches() > 0 ? 0.9 : 0.0;
        double fromPartial = Math.min(0.5, web.partialMatches() * 0.15);
        double fromPages = Math.min(0.3, web.pages().size() * 0.05);
        return Math.min(1.0, fromExact + fromPartial + fromPages);
    }
}
