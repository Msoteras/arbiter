package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * The ceiling the referente sets for a branch.
 *
 * @param maxAmount null clears the ceiling: the branch goes back to having none and the analyst
 *                  authorizes everything. That's a real choice, not an omission, which is why it's
 *                  the same endpoint rather than a DELETE — the screen has one field with one
 *                  meaning, and "vacío" is what removing a limit looks like there
 */
public record SettlementAuthorityUpsertRequest(
        @DecimalMin(value = "0.00", message = "maxAmount cannot be negative")
        BigDecimal maxAmount
) {}
