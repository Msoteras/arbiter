package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

/**
 * Internal result of the analysis. Support signals (risk score, forensic report, rule findings) are
 * attached by the orchestrator, never by the classifier, and stay null when they didn't run.
 */
@Builder(toBuilder = true)
public record ClassificationResponse(
        Classification classification,
        List<String> factors,
        double confidence,
        boolean deterministicFastTrack,
        RiskScore riskScore,
        String insuredName,
        ImageForensicReport forensicReport,
        List<RuleFinding> ruleFindings,
        /** Set by the classifier, unlike the fields above; null on every path that skips the LLM. */
        CauseConsistency causeConsistency,
        /** Claim cause the account describes, from the branch's catalog. Null unless CONTRADICTS. */
        String suggestedClaimCause,
        /** Verbatim sentence of the account backing the verdict. Null unless CONTRADICTS. */
        String causeEvidence
) {

    public ClassificationResponse(Classification classification, List<String> factors, double confidence, boolean deterministicFastTrack) {
        this(classification, factors, confidence, deterministicFastTrack,
                null, null, null, null, null, null, null);
    }
}
