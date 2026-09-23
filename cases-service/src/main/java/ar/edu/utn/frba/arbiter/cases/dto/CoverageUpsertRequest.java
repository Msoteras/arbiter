package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

/** Body for creating/updating a coverage from the referente's Coberturas tab. */
public record CoverageUpsertRequest(
        @NotBlank String name,
        String clause,
        @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") BigDecimal deductibleRatio,
        @Min(0) Integer reportingWindowDays,
        @Min(0) Integer maxAnnualClaims,
        /** Days after the policy starts during which this coverage doesn't apply yet. Null means none. */
        @Min(0) Integer waitingPeriodDays,
        boolean coversFamilyGroup,
        boolean claimExhaustsCoverage,
        /** Null means {@code TOTAL_LOSS}. */
        SettlementFormula settlementFormula,
        /** Null means {@code SUM_INSURED}. Only applies to total loss. */
        SettlementBasis settlementBasis,
        /** Fraction (0..1) of the ceiling paid from the year's second event on. Null means no reduction. */
        @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") BigDecimal secondEventRatio,
        boolean deductPendingInstallments,
        boolean deductOverdueBalance,
        List<String> exclusions) {}
