package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.ImageFraudAnalysisService;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import org.springframework.stereotype.Component;

/**
 * An exact web match is the strongest signal; partial matches are noisier; many pages suggest a
 * catalog image. Not evaluable when the web search didn't run, never a false 0.
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
            return Contribution.notEvaluable(factorId(),
                    "No se verificó contra internet — factor no evaluable");
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
                    "Ninguna imagen aparece en internet.");
        }

        return new Contribution(factorId(), score,
                "Una imagen aparece en internet, " + ImageFraudAnalysisService.webMatches(strongest) + ".");
    }

    private double gradeWebFinding(WebFinding web) {
        double fromExact = web.fullMatches() > 0 ? 0.9 : 0.0;
        double fromPartial = Math.min(0.5, web.partialMatches() * 0.15);
        double fromPages = Math.min(0.3, web.pages().size() * 0.05);
        return Math.min(1.0, fromExact + fromPartial + fromPages);
    }
}
