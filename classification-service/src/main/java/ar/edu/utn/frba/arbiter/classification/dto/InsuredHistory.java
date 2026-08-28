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
             * The policy the claim was made against. Without it there's no way to tell whether a
             * prior claim consumed <b>this</b> coverage or another policy's from the same insured,
             * which is what {@code claim_exhausts_coverage} needs (D9).
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
