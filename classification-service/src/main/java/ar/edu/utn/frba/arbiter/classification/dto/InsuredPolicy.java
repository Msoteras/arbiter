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
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<PolicyCoverage> coverages,
        List<String> applicableClauses
) {

    @Builder
    public record PolicyCoverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
