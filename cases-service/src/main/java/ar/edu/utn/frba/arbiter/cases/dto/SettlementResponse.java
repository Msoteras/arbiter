package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Either a proposal or the stored settlement. {@link #breakdown} is assembled server-side on purpose,
 * so the wording the analyst signs off on can't drift from the arithmetic.
 *
 * @param confirmed       false while this is only a proposal; nothing is persisted yet
 * @param status          null on an unconfirmed proposal
 * @param authorityLimit  the current ceiling on a proposal, the frozen one on a stored settlement.
 *                        Null means the branch has no ceiling
 * @param suggestedAmount read by the model off the file's paperwork. Never applied automatically:
 *                        the analyst has to take it deliberately
 * @param warnings        never blocking: the analyst can settle anyway and say why
 */
public record SettlementResponse(
        SettlementFormula formula,
        BigDecimal sumInsured,
        SettlementBasis settlementBasis,
        BigDecimal replacementValue,
        BigDecimal deductibleRate,
        Integer eventOrdinal,
        BigDecimal eventPercentage,
        Integer pendingInstallments,
        BigDecimal installmentAmount,
        BigDecimal deductibleAmount,
        BigDecimal pendingInstallmentsAmount,
        BigDecimal overdueBalanceAmount,
        BigDecimal calculatedAmount,
        BigDecimal settledAmount,
        String adjustmentReason,
        boolean confirmed,
        Instant confirmedAt,
        SettlementStatus status,
        BigDecimal authorityLimit,
        String returnReason,
        BigDecimal suggestedAmount,
        String suggestedFrom,
        SettlementSuggestionTarget suggestedFor,
        List<Line> breakdown,
        List<String> warnings
) {

    /**
     * @param kind {@code BASE}, {@code DEDUCTION} or {@code TOTAL}; drives styling and sign so the
     *             frontend doesn't infer them from the label
     */
    public record Line(String kind, String concept, String detail, BigDecimal amount) {

        public static Line base(String concept, String detail, BigDecimal amount) {
            return new Line("BASE", concept, detail, amount);
        }

        public static Line deduction(String concept, String detail, BigDecimal amount) {
            return new Line("DEDUCTION", concept, detail, amount);
        }

        public static Line total(String concept, BigDecimal amount) {
            return new Line("TOTAL", concept, null, amount);
        }
    }
}
