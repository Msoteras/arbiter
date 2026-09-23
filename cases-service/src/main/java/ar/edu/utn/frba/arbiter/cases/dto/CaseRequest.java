package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CaseRequest(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        @NotBlank String insuredItem,
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotBlank String description,
        @NotNull @PastOrPresent LocalDateTime eventDate,
        /** Street-level address only: locality and province travel in their own fields. */
        @NotBlank String eventLocation,
        String province,
        String locality,
        LocalDateTime policeReportAt,
        /** Optional. Zero is allowed: some intakes send it instead of null. */
        @PositiveOrZero BigDecimal claimedAmount,
        // PEP comes from the insurer's data, not the claim form. Ignored if sent.
        Boolean pep,
        // Image consent is captured during onboarding, not per claim. Ignored if sent.
        Boolean imageConsent,
        String contactEmail,
        String contactPhone
) {
}