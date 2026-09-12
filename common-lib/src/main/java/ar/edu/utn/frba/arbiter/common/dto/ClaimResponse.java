package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import lombok.Builder;

import java.util.List;

/**
 * Shared contract: polling response for a case's classification. Fields other than
 * {@code caseId} are null until the analysis finishes; the case lifecycle/state is
 * cases-service's responsibility.
 *
 * <p>{@code forensicReport} carries the structured image-fraud analysis for the analyst UI
 * (analyst-only — never shown to the insured). Null when no analysis ran: Fast Track, or a
 * claim with no image attachments.
 *
 * <p>The risk fields carry the parallel fraud/risk score alongside the classification, and travel
 * the same poll channel. {@code riskScore} and {@code riskBand} are {@code null} when the claim
 * wasn't scored (no scoring config for the branch/claim cause): that "sin scorear" state is
 * distinct from a real {@code LOW} band, so the read model must not collapse it to LOW.
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
        /** Insured's real name, resolved from the policy (InsurerAdapter) at classification time. */
        String insuredName,
        /**
         * Whether the insured's account matches the claim cause they declared. {@code null} on every
         * path that skips the model (Fast Track, a hard coverage exclusion, missing documentation),
         * same as the risk fields — absent is not {@code MATCHES}.
         */
        CauseConsistency causeConsistency,
        /**
         * The claim cause the account actually describes, when {@code causeConsistency} is
         * {@code CONTRADICTS}. Always a name from the branch's catalog: the output schema restricts
         * the model to that closed list, so it can't invent one. Null otherwise.
         */
        String suggestedClaimCause,
        /** Verbatim sentence from the account backing the verdict. Null when it matched. */
        String causeEvidence
) {}
