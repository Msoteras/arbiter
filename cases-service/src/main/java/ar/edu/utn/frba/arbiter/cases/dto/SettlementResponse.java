package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What gets paid on a claim, and the arithmetic behind it — either the proposal the analyst is
 * about to confirm or the settlement already authorized.
 *
 * <p>{@link #breakdown} is assembled on the server on purpose. The frontend showing "suma
 * asegurada − franquicia − cuotas" is not decoration: it's the explanation the analyst signs off
 * on, and rebuilding it in the SPA would let the wording and the arithmetic drift apart the first
 * time a deduction changes. The client renders lines, it doesn't compute them.
 *
 * @param confirmed        false while this is only a proposal — nothing is persisted yet
 * @param settledAmount    what the analyst authorized; null on an unconfirmed proposal
 * @param status           where it stands in the authorization chain. Null on an unconfirmed
 *                         proposal: nothing has been signed, so there is no stage to be at
 * @param authorityLimit   the branch ceiling that applied. On a proposal it's the current one, so
 *                         the analyst can see beforehand that this amount will need the referente;
 *                         on a stored settlement it's the one frozen when it was confirmed. Null
 *                         means the branch has no ceiling
 * @param returnReason     why the referente sent it back, when they did
 * @param suggestedAmount  what the model read off the file's own paperwork — the repair quote or
 *                         the purchase proof, depending on the formula. <b>A suggestion and
 *                         nothing else</b>: it is not applied, it does not move the calculation,
 *                         and the analyst has to take it deliberately. Null when there is no such
 *                         document, or the model couldn't read an amount off it
 * @param suggestedFrom    which document type it was read from, so the analyst can go and check it
 *                         before taking it. A number with no provenance is worth less than none
 * @param warnings         what the analyst should know before signing: data the calculation
 *                         couldn't find, or a deduction that came out at zero for a reason worth
 *                         stating. Never blocks — the analyst can settle anyway and say why
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
        List<Line> breakdown,
        List<String> warnings
) {

    /**
     * One row of the settlement sheet.
     *
     * @param kind   {@code BASE} for the ceiling the deductions come off, {@code DEDUCTION} for
     *               each thing subtracted, {@code TOTAL} for the result. Drives styling and sign,
     *               so the frontend doesn't have to infer either from the label
     * @param detail where the number comes from ("10% de $1.300.000", "7 cuotas × $26.000"). It's
     *               the difference between an analyst who can defend the amount and one reading a
     *               figure off a screen
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
