package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One settlement the referente already authorized, for the "Autorizadas" tab of their queue.
 * Same case data as {@link PendingSettlementResponse}, plus who signed it and when.
 *
 * @param authorizedByName null if that referente has no profile in this insurer's schema
 */
public record AuthorizedSettlementResponse(
        Long caseId,
        String insuredName,
        String branch,
        String claimCause,
        String analystName,
        BigDecimal calculatedAmount,
        BigDecimal settledAmount,
        String adjustmentReason,
        BigDecimal authorityLimit,
        BigDecimal excess,
        Instant confirmedAt,
        Instant authorizedAt,
        String authorizedByName
) {}
