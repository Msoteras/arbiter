package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** Peso de un factor de riesgo dentro de un {@code ScoringConfig} — "peso_factor" en el DER. */
public record FactorWeightDto(
        @NotBlank String factorId,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal weight) {}
