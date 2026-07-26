package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

/**
 * Contradictions across the claim's documents (e.g. amounts, dates or items that don't match
 * between the police report, the invoice and the narrative). H0012 acceptance criterion, wired in
 * as a stub for Milestone 1: cross-document analysis isn't implemented yet, so it always reports a
 * neutral 0.0 rather than guessing. It stays in the factor list so an insurer can already weigh it
 * and the gauge breakdown shows it, and the real logic drops in behind this same contract later.
 */
@Component
public class DocumentInconsistencyEvaluator implements RiskFactorEvaluator {

    @Override
    public String factorId() {
        return RiskFactorIds.DOCUMENT_INCONSISTENCY;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        // Stub: no cross-document logic yet, so it can't evaluate anything — declare it non-evaluable
        // rather than fabricate a 0.0 that would drag the score down when an insurer activates it.
        return Contribution.notEvaluable(factorId(),
                "Análisis de inconsistencias documentales pendiente (stub) — factor no evaluable");
    }
}
