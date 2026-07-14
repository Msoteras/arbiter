package ar.edu.utn.frba.arbiter.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared contract: the facts of a claim, sent by cases-service to classification-service
 * for analysis. {@code attachmentsOcr} carries already-extracted document text when the
 * caller has it (classification fills it in internally otherwise).
 */
@Builder
public record ClaimReport(
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
        List<String> attachmentsOcr
) {}
