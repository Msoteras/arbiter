package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param excess     how much the amount overshoots the ceiling
 * @param waitingFor days waiting; the art. 56 LS 30-day window keeps running meanwhile
 */
public record PendingSettlementResponse(
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
        long waitingFor
) {}
