package ar.edu.utn.frba.arbiter.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Without {@code attachmentsOcr}, classification-service reads the files itself. */
@Builder
public record ClaimReport(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        // Business rules (Fast Track, scoring) are scoped by branch + coverage, not by claim cause.
        Long coverageId,
        // Redundant on purpose: the insurer database only knows coverages by name.
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
        // The insured's statement, not the certificate's date: crossing the two is the signal.
        LocalDateTime policeReportAt,
        // External web search consent (CLIP runs regardless). Boxed: null means NO, so it fails closed.
        Boolean imageConsent,
        List<String> attachmentsOcr,
        // Claims filed through Arbiter: the insurer's history never receives them.
        List<PriorClaim> priorClaims
) {

    public ClaimReport {
        priorClaims = priorClaims == null ? List.of() : List.copyOf(priorClaims);
    }
}
