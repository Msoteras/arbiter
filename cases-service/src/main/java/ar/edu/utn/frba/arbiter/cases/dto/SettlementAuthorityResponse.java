package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param maxAmount       null when the branch has no ceiling: the analyst authorizes everything
 * @param updatedByUserId null on a branch with no ceiling
 */
public record SettlementAuthorityResponse(
        Long branchId,
        String branchName,
        BigDecimal maxAmount,
        Instant updatedAt,
        Long updatedByUserId
) {}
