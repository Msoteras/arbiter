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
            /**
             * The coverage that answered for the claim. What the sum-insured exhaustion rule
             * accumulates against: the limit belongs to the coverage, not to the policy — there is
             * no aggregate policy ceiling (confirmed with the analyst, 01/09/2026), so a settled
             * robo consumes the robo coverage and leaves the hurto one untouched.
             *
             * <p>Null when the company's record doesn't say. The rule then leaves that claim out
             * rather than imputing it to a coverage by guessing, which is the same criterion the
             * rest of the hard rules use for missing data.
             */
            String coverageName,
            String claimCause,
            String affectedItem,
            String status,
            BigDecimal amountClaimed,
            BigDecimal amountSettled,
            String notes
    ) {}
}
