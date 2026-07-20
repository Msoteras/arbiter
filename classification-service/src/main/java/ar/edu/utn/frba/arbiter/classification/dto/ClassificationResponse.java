package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

/**
 * Internal result of the analysis. {@code riskScore} is the parallel fraud/risk signal, attached
 * downstream by the orchestrator and never set by the classifier. It is fully optional: when
 * scoring didn't run (no config) or failed, it stays {@code null} and the classification is still
 * valid — the score is a support signal and must never break the classification.
 */
@Builder(toBuilder = true)
public record ClassificationResponse(
        Classification classification,
        List<String> factors,
        double confidence,
        boolean deterministicFastTrack,
        RiskScore riskScore,
        /** Insured's real name (from InsuredPolicy), attached by the orchestrator like riskScore. */
        String insuredName
) {

    /**
     * Classifier-facing constructor without a score/name: the classifier produces the
     * classification and never sets these — the orchestrator attaches them afterwards. Keeps the
     * classifier decoupled from scoring and policy lookup.
     */
    public ClassificationResponse(Classification classification, List<String> factors, double confidence, boolean deterministicFastTrack) {
        this(classification, factors, confidence, deterministicFastTrack, null, null);
    }
}
