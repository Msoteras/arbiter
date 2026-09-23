package ar.edu.utn.frba.arbiter.cases.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code effectiveFrom}/{@code effectiveTo} carry the time, not just the date: coverage starts at an
 * exact hour, and comparing by date alone accepts claims from earlier that same day.
 */
@Builder
public record PolicyResponse(
        String policyNumber,
        String insurerId,
        String insurerName,
        String insuredName,
        String insuredId,
        String contactEmail,
        String contactPhone,
        String branch,
        String insuredItem,
        String product,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        Validity validity,
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<Coverage> coverages
) {

    /**
     * Computed server-side rather than derived by the client: the source timestamps have no zone,
     * and a UTC backend and an Argentine browser would disagree near expiry.
     */
    public enum Validity {
        CURRENT,
        NOT_YET_ACTIVE,
        EXPIRED;

        public static Validity at(LocalDateTime from, LocalDateTime to, LocalDateTime now) {
            if (to != null && to.isBefore(now)) {
                return EXPIRED;
            }
            return from != null && from.isAfter(now) ? NOT_YET_ACTIVE : CURRENT;
        }
    }

    /**
     * @param deductible    absolute amount, already computed over {@code insuredAmount}; what the rules read
     * @param deductiblePct percentage points (10.00 = 10%). The raw value persisted in
     *                      {@code policy_coverage}, so it doesn't go stale when the sum insured changes
     */
    @Builder
    public record Coverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible,
            BigDecimal deductiblePct
    ) {}
}
