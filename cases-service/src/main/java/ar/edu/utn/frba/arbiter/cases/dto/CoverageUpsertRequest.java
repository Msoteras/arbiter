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
        /**
         * Carencia: días desde el alta de la póliza en que la cobertura todavía no aplica, aunque
         * haya contrato. {@code null} = sin carencia. La evalúa {@code TemporalRuleEvaluator}.
         */
        @Min(0) Integer waitingPeriodDays,
        /** Si la cobertura alcanza al grupo familiar conviviente o solo al titular. */
        boolean coversFamilyGroup,
        /** Si un siniestro liquidado agota la cobertura para el período. */
        boolean claimExhaustsCoverage,
        List<String> exclusions) {}
