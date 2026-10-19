package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    /** Insurer DB value ({@code estado_resolucion}) of a paid claim. */
    private static final String SETTLED = "LIQUIDADO";

    /**
     * Which event of the policy year the claim under analysis is, starting at 1. Shared by the
     * events-per-year rule and the settlement so both count the window identically.
     *
     * <p>Counts the settled claims of the same policy, across all its coverages: the contracts cap
     * indemnities "per policy" and "per year of validity". Rejected claims and claims with no
     * policy number don't count; when in doubt, the contract reads in favor of the insured.
     */
    public int eventOrdinalFor(LocalDate eventDate, InsuredPolicy policy) {
        if (eventDate == null || policy == null || policy.policyNumber() == null || claims == null) {
            return 1;
        }
        LocalDate policyStart = policy.effectiveFrom() == null ? null : policy.effectiveFrom().toLocalDate();
        LocalDate windowStart = policyYearStart(eventDate, policyStart);
        long priorInWindow = claims.stream()
                .filter(record -> record.date() != null)
                .filter(record -> policy.policyNumber().equals(record.policyNumber()))
                .filter(record -> SETTLED.equals(record.status()))
                .filter(record -> !record.date().isBefore(windowStart) && !record.date().isAfter(eventDate))
                .count();
        return (int) priorInWindow + 1;
    }

    /**
     * The last anniversary of the policy start on or before the event. Without a start date, the
     * last 12 months: the closest window to a policy year.
     */
    static LocalDate policyYearStart(LocalDate eventDate, LocalDate policyStart) {
        if (policyStart == null) {
            return eventDate.minusYears(1);
        }
        if (eventDate.isBefore(policyStart)) {
            return policyStart;
        }
        long years = ChronoUnit.YEARS.between(policyStart, eventDate);
        // A start on Feb 29 has its anniversary on Feb 28 in common years, which YEARS.between misses.
        if (!policyStart.plusYears(years + 1).isAfter(eventDate)) {
            years++;
        }
        return policyStart.plusYears(years);
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
