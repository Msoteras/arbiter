package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record InsuredPolicy(
        String policyNumber,
        String insuredName,
        String insuredId,
        String branch,
        String product,
        /** The covered item as the insurer has it (not what the insured declared). */
        String insuredItem,
        /**
         * The device's IMEI when the branch has one (Celulares); null where it doesn't apply. It's
         * the operand the IMEI appearing in the attached documents is crossed against (D4b).
         */
        String imei,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<PolicyCoverage> coverages,
        List<String> applicableClauses
) {

    /**
     * Whether the policy temporally covered that date. It lives here because the validity window
     * belongs to the policy and two places already ask for it: the hard rule D13
     * ({@code TemporalRuleEvaluator}) and the audited snapshot ({@code policy_snapshot.in_force}).
     * Duplicated, one day one of them starts using {@code isAfter} where the other uses
     * {@code isBefore} and nobody notices.
     *
     * <p>With no dates it returns {@code false}: validity that couldn't be verified isn't asserted —
     * same criterion as the policy holder in D2. The raw data, nulls included, still lands in
     * {@code policy_snapshot.insurer_db_payload}.
     */
    public boolean inForceOn(LocalDate date) {
        if (date == null || effectiveFrom == null || effectiveTo == null) {
            return false;
        }
        return !date.isBefore(effectiveFrom) && !date.isAfter(effectiveTo);
    }

    @Builder
    public record PolicyCoverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
