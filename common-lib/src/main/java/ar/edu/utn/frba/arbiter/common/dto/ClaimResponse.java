package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Polling response for a case's classification. Fields other than {@code caseId} are null until
 * the analysis finishes. {@code forensicReport} is analyst-only.
 *
 * <p>{@code riskScore}/{@code riskBand} are null when the claim wasn't scored (no scoring config):
 * that state must not be collapsed to {@code LOW}.
 */
@Builder
public record ClaimResponse(
        Long caseId,
        Classification classification,
        Double confidence,
        List<String> factors,
        boolean deterministicFastTrack,
        ImageForensicReport forensicReport,
        Double riskScore,
        RiskBand riskBand,
        List<RiskBreakdownItem> riskBreakdown,
        String insuredName,
        // Null on every path that skips the model (Fast Track, exclusion, missing docs): absent is not MATCHES.
        CauseConsistency causeConsistency,
        // Set only on CONTRADICTS; always a name from the branch's closed catalog.
        String suggestedClaimCause,
        String causeEvidence,
        // llm_analysis is append-only, so a reclassified case still has the older row on file; the
        // poller compares this timestamp to tell a fresh answer from a stale one. Null for Fast Track.
        Instant analyzedAt
) {}
