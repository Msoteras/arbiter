package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record HistorialAsegurado(
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
