package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record InsuredPolicy(
        String policyNumber,
        String insuredName,
        String insuredId,
        String branch,
        String product,
        /** As the insurer has it, not as the insured declared it. */
        String insuredItem,
        String imei,
        // Date and time: policies start at an exact hour, and comparing dates only accepts
        // same-day claims made before coverage started.
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        boolean upToDate,
        BigDecimal installmentAmount,
        BigDecimal overdueBalance,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<PolicyCoverage> coverages,
        List<String> applicableClauses
) {

    /**
     * Shared by the policy-in-force rule and {@code policy_snapshot.in_force} so both agree. Without
     * dates it returns {@code false}: validity that can't be verified isn't asserted.
     */
    public boolean inForceOn(LocalDateTime instant) {
        if (instant == null || effectiveFrom == null || effectiveTo == null) {
            return false;
        }
        return !instant.isBefore(effectiveFrom) && !instant.isAfter(effectiveTo);
    }

    /**
     * The same policy with {@code insuredAmount}/{@code deductible} narrowed to the coverage that
     * answers for the claim; the whole engine reads those two fields. An unknown name leaves the
     * policy untouched, since a null sum insured would silently disable every rule that divides by it.
     */
    public InsuredPolicy forCoverage(String coverageName) {
        if (coverageName == null || coverages == null) {
            return this;
        }
        return coverages.stream()
                .filter(coverage -> coverageName.equalsIgnoreCase(coverage.description()))
                .findFirst()
                .map(coverage -> InsuredPolicy.builder()
                        .policyNumber(policyNumber)
                        .insuredName(insuredName)
                        .insuredId(insuredId)
                        .branch(branch)
                        .product(product)
                        .insuredItem(insuredItem)
                        .imei(imei)
                        .effectiveFrom(effectiveFrom)
                        .effectiveTo(effectiveTo)
                        .upToDate(upToDate)
                        // Policy-level, not coverage-level: the settlement still needs them.
                        .installmentAmount(installmentAmount)
                        .overdueBalance(overdueBalance)
                        .insuredAmount(coverage.insuredAmount())
                        .deductible(coverage.deductible())
                        .coverages(coverages)
                        .applicableClauses(applicableClauses)
                        .build())
                .orElse(this);
    }

    @Builder
    public record PolicyCoverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
