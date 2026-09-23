package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record InsuredHistory(
        String insuredId,
        int previousClaimsCount,
        /** Despite the name, the sum of {@code amountSettled} across all the insured's policies. */
        BigDecimal totalAmountClaimed,
        LocalDate customerSince,
        List<ClaimRecord> claims
) {

    /**
     * Which event of the rolling year (same branch) the claim under analysis is, starting at 1.
     * Shared by the events-per-year rule and the settlement so both count the window identically.
     * A null branch doesn't filter.
     */
    public int eventOrdinalFor(LocalDate eventDate, String branch) {
        if (eventDate == null || claims == null) {
            return 1;
        }
        LocalDate windowStart = eventDate.minusYears(1);
        long priorInWindow = claims.stream()
                .filter(record -> record.date() != null)
                .filter(record -> branch == null || branch.equalsIgnoreCase(record.branch()))
                .filter(record -> !record.date().isBefore(windowStart) && !record.date().isAfter(eventDate))
                .count();
        return (int) priorInWindow + 1;
    }

    @Builder
    public record ClaimRecord(
            String claimId,
            LocalDate date,
            String policyNumber,
            String branch,
            /**
             * The sum-insured exhaustion rule accumulates per coverage (there is no policy-wide
             * ceiling). Null when unknown, and then the claim is left out rather than guessed.
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
