package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record InsuredHistory(
        String insuredId,
        int previousClaimsCount,
        BigDecimal totalAmountClaimed,
        LocalDate customerSince,
        List<ClaimRecord> claims
) {

    @Builder
    public record ClaimRecord(
            String claimId,
            LocalDate date,
            String branch,
            String claimCause,
            String affectedItem,
            String status,
            BigDecimal amountClaimed,
            BigDecimal amountSettled,
            String notes
    ) {}
}
