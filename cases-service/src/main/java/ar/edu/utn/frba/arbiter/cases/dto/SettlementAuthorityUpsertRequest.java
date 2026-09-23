package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * @param maxAmount null clears the ceiling, so the analyst authorizes everything in that branch
 */
public record SettlementAuthorityUpsertRequest(
        @DecimalMin(value = "0.00", message = "maxAmount cannot be negative")
        BigDecimal maxAmount
) {}
