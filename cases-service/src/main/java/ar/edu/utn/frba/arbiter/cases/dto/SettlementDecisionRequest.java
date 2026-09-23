package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Travels inside {@link AnalystDecisionRequest} so determining the amount and approving are a single
 * act, never a settlement on a case that wasn't approved.
 *
 * @param replacementValue optional; only caps coverages settled by the lesser of sum insured and replacement value
 * @param settledAmount    sent even when it matches the proposal: confirming the number is the recorded act
 * @param adjustmentReason required exactly when the amount differs from the formula
 */
public record SettlementDecisionRequest(
        @DecimalMin(value = "0.00", message = "replacementValue cannot be negative")
        BigDecimal replacementValue,

        @NotNull(message = "settledAmount is required")
        @DecimalMin(value = "0.00", message = "settledAmount cannot be negative")
        BigDecimal settledAmount,

        String adjustmentReason
) {}
