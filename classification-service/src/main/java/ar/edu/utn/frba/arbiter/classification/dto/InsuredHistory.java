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
            /**
             * La póliza sobre la que se hizo el siniestro. Sin esto no se puede saber si un
             * siniestro previo consumió <b>esta</b> cobertura o la de otra póliza del mismo
             * asegurado, que es lo que necesita {@code claim_exhausts_coverage} (D9).
             */
            String policyNumber,
            String branch,
            String claimCause,
            String affectedItem,
            String status,
            BigDecimal amountClaimed,
            BigDecimal amountSettled,
            String notes
    ) {}
}
