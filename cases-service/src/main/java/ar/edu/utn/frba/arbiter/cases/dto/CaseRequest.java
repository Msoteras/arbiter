package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @NotNull LocalDateTime eventDate,
        @NotBlank String eventLocation,
        BigDecimal claimedAmount,
        @NotNull Boolean pep,
        String contactEmail,
        String contactPhone
) {
}