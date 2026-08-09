package ar.edu.utn.frba.arbiter.cases.dto;

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
        List<String> exclusions) {}
