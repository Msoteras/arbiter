package ar.edu.utn.frba.arbiter.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The facts of a claim, sent by cases-service to classification-service for analysis.
 * {@code attachmentsOcr} is optional: classification extracts the text itself otherwise.
 */
@Builder
public record ClaimReport(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        // Business rules (Fast Track, scoring) are scoped by branch + coverage, not by claim cause.
        Long coverageId,
        // Redundant with the id on purpose: the insurer DB, where the sum insured and deductible
        // come from, only knows coverages by name.
        String coverageName,
        // Exclusions match by id because claim cause names repeat across branches.
        Long claimCauseId,
        @NotBlank String insuredItem,
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotBlank String description,
        @NotNull LocalDateTime eventDate,
        @NotBlank String eventLocation,
        BigDecimal claimedAmount,
        LocalDateTime reportedAt,
        // The insured's statement, not the certificate's date: extraction reads that one separately
        // and crossing the two is the signal. Null when the claim cause involves no police report.
        LocalDateTime policeReportAt,
        // Consent to send images to the external web search; internal CLIP analysis runs regardless.
        // Boxed and null means NO: Jackson 3 rejects a missing primitive, and consent must fail closed.
        Boolean imageConsent,
        List<String> attachmentsOcr,
        // The insured's other claims filed through Arbiter, merged with the company's history so
        // the annual cap and Fast Track count both sources.
        List<PriorClaim> priorClaims
) {

    public ClaimReport {
        priorClaims = priorClaims == null ? List.of() : List.copyOf(priorClaims);
    }
}
