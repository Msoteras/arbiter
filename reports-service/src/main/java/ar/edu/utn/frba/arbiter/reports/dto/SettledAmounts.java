package ar.edu.utn.frba.arbiter.reports.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the insurer committed to pay in the period and how it got there from what was claimed.
 *
 * <p>Only <b>authorized</b> settlements: one awaiting the referent's signature can still come back and
 * be redone for another amount. Anchored to the confirmation date, when the obligation arises.
 *
 * @param average      null without settlements
 * @param claimedCases settlements whose claimed amount is known. It is optional on the claim, so the
 *                     screen needs this to tell whether "settled vs claimed" covers the whole period
 * @param installments deducted for policy installments not yet paid
 * @param overdue      deducted for overdue balance
 */
public record SettledAmounts(
        long settlements,
        BigDecimal settled,
        BigDecimal average,
        BigDecimal claimed,
        long claimedCases,
        BigDecimal deductible,
        BigDecimal installments,
        BigDecimal overdue
) {

    public static final SettledAmounts NONE = new SettledAmounts(
            0, BigDecimal.ZERO, null, BigDecimal.ZERO, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public static SettledAmounts of(long settlements, BigDecimal settled, BigDecimal claimed,
                                    long claimedCases, BigDecimal deductible,
                                    BigDecimal installments, BigDecimal overdue) {
        BigDecimal average = settlements == 0
                ? null
                : settled.divide(BigDecimal.valueOf(settlements), 2, RoundingMode.HALF_UP);
        return new SettledAmounts(settlements, settled, average, claimed, claimedCases,
                deductible, installments, overdue);
    }
}
