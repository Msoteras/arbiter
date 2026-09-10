package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The ceiling an analyst can authorize on their own in one branch.
 *
 * @param maxAmount null when the branch has no ceiling — the analyst authorizes everything, which
 *                  is the state of a branch the referente never configured. It's a real answer,
 *                  not missing data, so the screen says "sin tope" instead of an empty field
 */
public record SettlementAuthorityResponse(
        Long branchId,
        String branchName,
        BigDecimal maxAmount,
        Instant updatedAt
) {}
