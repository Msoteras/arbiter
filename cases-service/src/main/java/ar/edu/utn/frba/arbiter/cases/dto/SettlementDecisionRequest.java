package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The amount the analyst authorizes, sent together with an approval.
 *
 * <p>It travels inside {@link AnalystDecisionRequest} rather than through an endpoint of its own
 * so that determining the amount and resolving the claim are a single act. Two calls would leave
 * room for a settlement recorded against a case that never got approved, which is a row nobody
 * could explain.
 *
 * @param replacementValue what the analyst accredited from the file — an invoice, a ticket, a
 *                         repair quote. Optional: it only caps anything on coverages whose basis
 *                         is the lesser of sum insured and replacement cost, and even there a
 *                         missing value means the sum insured stands
 * @param settledAmount    what actually gets paid. Sent even when it matches the proposal: the
 *                         analyst confirming a number is the act being recorded, and inferring it
 *                         from silence would make an approval and an unread screen look the same
 * @param adjustmentReason why it differs from what the formula produced. Required exactly when it
 *                         does differ — {@code SettlementService} enforces that, because the rule
 *                         compares two values and no field-level annotation can see both
 */
public record SettlementDecisionRequest(
        @DecimalMin(value = "0.00", message = "replacementValue cannot be negative")
        BigDecimal replacementValue,

        @NotNull(message = "settledAmount is required")
        @DecimalMin(value = "0.00", message = "settledAmount cannot be negative")
        BigDecimal settledAmount,

        String adjustmentReason
) {}
