package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** Umbral que mapea un score normalizado a una RiskBand — "configuracion_banda" en el DER. */
public record ScoreBandCutDto(
        @NotBlank String band,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minScoreInclusive) {}
