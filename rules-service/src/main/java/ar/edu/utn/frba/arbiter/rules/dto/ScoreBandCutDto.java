package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ScoreBandCutDto(
        @NotBlank String band,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minScoreInclusive) {}
