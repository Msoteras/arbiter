package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** Threshold mapping a normalized score to a RiskBand — "configuracion_banda" in the DER. */
public record ScoreBandCutDto(
        @NotBlank String band,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minScoreInclusive) {}
