package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

/**
 * Internal result of the analysis. {@code riskScore} is the parallel fraud/risk signal, and
 * {@code forensicReport} the structured image-fraud analysis — both attached downstream by the
 * orchestrator and never set by the classifier. All optional: when they didn't run (no config,
 * Fast Track, no images) they stay {@code null} and the classification is still valid — support
 * signals must never break the classification.
 */
@Builder(toBuilder = true)
public record ClassificationResponse(
        Classification classification,
        List<String> factors,
        double confidence,
        boolean deterministicFastTrack,
        RiskScore riskScore,
        /** Insured's real name (from InsuredPolicy), attached by the orchestrator like riskScore. */
        String insuredName,
        /** Structured image-fraud analysis, attached by the orchestrator for persistence + UI. */
        ImageForensicReport forensicReport,
        /**
         * Hard-rule evaluations (coverage exclusions) that ran deterministically before the LLM.
         * Attached by the orchestrator and persisted to {@code rule_result} for the audit trail;
         * empty when no evaluable rule applied. Never set by the classifier.
         */
        List<RuleFinding> ruleFindings
) {

    /**
     * Classifier-facing constructor without a score/name/report: the classifier produces the
     * classification and never sets these — the orchestrator attaches them afterwards. Keeps the
     * classifier decoupled from scoring and policy lookup.
     */
    public ClassificationResponse(Classification classification, List<String> factors, double confidence, boolean deterministicFastTrack) {
        this(classification, factors, confidence, deterministicFastTrack, null, null, null, null);
    }
}
