package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
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
        /**
         * Cómo se calcula el techo indemnizable: la suma asegurada, o el menor entre ésa y el
         * valor de reposición acreditado (art. 7, Bases de Indemnización). {@code null} se toma
         * como {@code SUM_INSURED}.
         */
        SettlementBasis settlementBasis,
        /**
         * Porcentaje del techo que se paga del segundo evento del año en adelante, como fracción
         * 0..1 igual que {@code deductibleRatio} (0.5 = 50%). {@code null} = el número de evento
         * no reduce nada.
         */
        @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") BigDecimal secondEventRatio,
        /** Si se descuentan las cuotas del premio que quedan por vencer (pérdida total). */
        boolean deductPendingInstallments,
        /** Si se descuenta el saldo impago del contrato (cláusula 102, art. 5). */
        boolean deductOverdueBalance,
        List<String> exclusions) {}
