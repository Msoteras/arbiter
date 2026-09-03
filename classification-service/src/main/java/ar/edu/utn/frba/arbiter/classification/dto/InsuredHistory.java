package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record InsuredHistory(
        String insuredId,
        int previousClaimsCount,
        /**
         * Sum of {@code amountSettled} over {@link #claims} — what the company paid, not what was
         * claimed. The name is a misnomer kept for stability. Scoped to the insured, so it spans
         * every policy and branch they hold: it says nothing about what this coverage has left
         * (that's {@code claim_exhausts_coverage}, counted per policy).
         */
        BigDecimal totalAmountClaimed,
        LocalDate customerSince,
        List<ClaimRecord> claims
) {

    /**
     * Which event of the rolling year the claim being analyzed is: 1 when it's the first, 2 when
     * one prior claim in the same branch falls in the twelve months before it, and so on.
     *
     * <p>It lives here, like {@code InsuredPolicy.inForceOn}, because two places ask the same
     * question of the same window and must not answer it differently: the hard rule D10
     * ({@code TemporalRuleEvaluator}, which caps how many events a year the coverage allows) and
     * the settlement (which pays the second event of the year at a reduced percentage). One
     * counting {@code isBefore} where the other counts {@code isAfter} would have the analyst
     * approving an event the rules called out of quota, or paying 100% of a second event.
     *
     * <p>A null branch doesn't filter — matching what the rule already did, on the grounds that a
     * claim with no branch is bad data and narrowing on it would silently undercount.
     *
     * @return at least 1; the claim under analysis is not in {@code claims} yet, so it's the
     *         (priors + 1)-th
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
