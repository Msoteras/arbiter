package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the referente's authorization queue: a settlement over the branch's attribution,
 * with just enough of its case to decide without opening it.
 *
 * @param excess     how much the amount overshoots the ceiling. It's the number the referente is
 *                   actually judging — an amount $2.000 over a $500.000 limit and one $800.000
 *                   over read completely differently, and making them subtract it themselves on
 *                   every row is how a queue stops being read
 * @param waitingFor days the settlement has been waiting. The claim is burning its 30-day legal
 *                   window while it sits here (art. 56 LS)
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
