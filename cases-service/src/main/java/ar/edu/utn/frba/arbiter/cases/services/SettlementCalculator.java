package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Works out what a claim should pay. Pure arithmetic over the case, the coverage the referente
 * configured and the policy snapshot frozen at classification time — no repositories, no state,
 * so the same inputs always give the same number and the whole thing is testable without a DB.
 *
 * <p>The formula is not ours. It is spelled out in the insurer's product manuals, and this class
 * is a transcription of them:
 *
 * <pre>
 *   techo            = suma asegurada  (o el menor entre ésa y el valor de reposición)
 *   tope del evento  = techo × % del evento        (2º evento del año → 50%)
 *   − franquicia     = suma asegurada × franquicia%
 *   − cuotas a vencer= cuotas que restan × importe de cuota
 *   − deuda vencida  = saldo impago del contrato
 *   = monto a pagar  (nunca negativo)
 * </pre>
 *
 * <p>Sources, in order: the Celulares manual ("La suma asegurada menos la franquicia menos las
 * cuotas pendientes de pago"); article 7 of clause 340, Bases de Indemnización, for the ceiling
 * being the <i>lesser</i> of sum insured and replacement cost; the Tecnología Portátil particular
 * conditions for the second event at 50%; and article 5 of clause 102 for the arrears deduction.
 *
 * <p><b>The franchise is a percentage of the sum insured, not of the amount being paid.</b> That's
 * the literal reading of both policies ("Franquicia 10% de la suma asegurada") and of the worked
 * example in the Celulares manual: $300.000 insured, $30.000 franchise. It matters on a second
 * event, where the ceiling drops to 50% but the franchise doesn't.
 *
 * <p>Nothing here decides anything: it produces a proposal the analyst confirms or adjusts. The
 * risk score deliberately plays no part — a suspicious claim gets rejected or sent to an expert,
 * it does not get quietly paid less.
 */
@Service
public class SettlementCalculator {

    /** Money is rounded to cents at every step, the way the settlement sheet is read. */
    private static final int MONEY_SCALE = 2;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FULL_PERCENTAGE = new BigDecimal("100.00");

    /** Total loss — the only formula so far. Repair after attempted robbery is a separate one. */
    public static final String TOTAL_LOSS = "TOTAL_LOSS";

    /**
     * Builds the proposal. Not persisted and with no id: the caller decides whether this is a
     * fresh row or overwrites the standing proposal.
     *
     * @param replacementValue what the analyst accredited from the file, or null if they haven't
     *                         recorded one yet
     */
    public CaseSettlement calculate(Case caseRecord, Coverage coverage, PolicyCoverage policyCoverage,
                                    PolicySnapshot snapshot, BigDecimal replacementValue) {
        BigDecimal sumInsured = sumInsured(policyCoverage, snapshot);
        SettlementBasis basis = coverage.getSettlementBasis() == null
                ? SettlementBasis.SUM_INSURED
                : coverage.getSettlementBasis();

        BigDecimal ceiling = ceiling(basis, sumInsured, replacementValue);

        int eventOrdinal = eventOrdinal(snapshot);
        BigDecimal eventPercentage = eventPercentage(coverage, eventOrdinal);
        BigDecimal cappedAmount = percentageOf(ceiling, eventPercentage);

        BigDecimal deductibleAmount = percentageOf(sumInsured, deductibleRate(coverage, policyCoverage));

        int pendingInstallments = coverage.isDeductPendingInstallments()
                ? pendingInstallments(caseRecord, snapshot)
                : 0;
        BigDecimal installmentAmount = snapshot == null ? null : snapshot.getInstallmentAmount();
        BigDecimal pendingInstallmentsAmount = money(
                BigDecimal.valueOf(pendingInstallments).multiply(orZero(installmentAmount)));

        BigDecimal overdueBalanceAmount = coverage.isDeductOverdueBalance() && snapshot != null
                ? money(orZero(snapshot.getOverdueBalance()))
                : BigDecimal.ZERO;

        BigDecimal calculated = cappedAmount
                .subtract(deductibleAmount)
                .subtract(pendingInstallmentsAmount)
                .subtract(overdueBalanceAmount);
        // Deductions bigger than the ceiling mean the insured is owed nothing, not that they owe
        // the company: whatever is left over is a debt of the policy, and collecting it is not
        // this claim's business.
        if (calculated.signum() < 0) {
            calculated = BigDecimal.ZERO;
        }

        return CaseSettlement.builder()
                .caseId(caseRecord.getId())
                .formula(TOTAL_LOSS)
                .sumInsured(sumInsured)
                .settlementBasis(basis)
                .replacementValue(replacementValue)
                .deductibleRate(deductibleRate(coverage, policyCoverage))
                .eventOrdinal(eventOrdinal)
                .eventPercentage(eventPercentage)
                .pendingInstallments(pendingInstallments)
                .installmentAmount(installmentAmount)
                .deductibleAmount(deductibleAmount)
                .pendingInstallmentsAmount(pendingInstallmentsAmount)
                .overdueBalanceAmount(overdueBalanceAmount)
                .calculatedAmount(money(calculated))
                .coverageId(coverage.getId())
                .policySnapshotId(snapshot == null ? null : snapshot.getId())
                .calculatedAt(Instant.now())
                .build();
    }

    /**
     * The snapshot first, the synced {@code policy_coverage} only as a fallback. The snapshot is
     * what the insurer answered when this claim was filed; the local copy keeps being re-synced, so
     * using it would let a settlement drift after the fact. Cases filed before the snapshot existed
     * fall back rather than refusing to be settled.
     *
     * <p>The fallback reads the coverage's sum insured and not the policy's, because a policy
     * doesn't have one: it covers robo and hurto with a different amount each, and there is no
     * aggregate ceiling over them.
     */
    private BigDecimal sumInsured(PolicyCoverage policyCoverage, PolicySnapshot snapshot) {
        if (snapshot != null && snapshot.getSumInsured() != null) {
            return money(snapshot.getSumInsured());
        }
        if (policyCoverage != null && policyCoverage.getSumInsured() != null) {
            return money(policyCoverage.getSumInsured());
        }
        return BigDecimal.ZERO;
    }

    /**
     * The franchise this policy actually contracted for this coverage, falling back to the rate the
     * referente configured on the coverage.
     *
     * <p>The order matters and it isn't the obvious one: {@code coverage.deductible} is the
     * insurer's default for that risk, while {@code policy_coverage.deductible_pct} is the term
     * written into <b>this</b> contract, synced from their DB. When they differ it's because this
     * policy was sold with a different franchise, and the contract wins — the analyst is deducting
     * from what a specific insured is owed, not from an average.
     */
    private BigDecimal deductibleRate(Coverage coverage, PolicyCoverage policyCoverage) {
        if (policyCoverage != null && policyCoverage.getDeductiblePct() != null) {
            return policyCoverage.getDeductiblePct();
        }
        return coverage.getDeductible();
    }

    /**
     * With no accredited replacement value, {@code LESSER_OF_SUM_AND_REPLACEMENT} falls back to
     * the sum insured: the ceiling can't be lowered by a number nobody produced.
     */
    private BigDecimal ceiling(SettlementBasis basis, BigDecimal sumInsured, BigDecimal replacementValue) {
        if (basis != SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT
                || replacementValue == null || replacementValue.signum() <= 0) {
            return sumInsured;
        }
        return sumInsured.min(money(replacementValue));
    }

    /** Frozen by classification-service; 1 when the snapshot predates the column. */
    private int eventOrdinal(PolicySnapshot snapshot) {
        if (snapshot == null || snapshot.getEventsInYear() == null || snapshot.getEventsInYear() < 1) {
            return 1;
        }
        return snapshot.getEventsInYear();
    }

    /**
     * The first event of the year is always worth 100%. From the second on, the coverage's reduced
     * rate applies — where the referente left it unset, nothing is reduced, which is the Celulares
     * case: that product allows one event a year, so there is no second one to price.
     */
    private BigDecimal eventPercentage(Coverage coverage, int eventOrdinal) {
        if (eventOrdinal < 2 || coverage.getSecondEventPercentage() == null) {
            return FULL_PERCENTAGE;
        }
        return coverage.getSecondEventPercentage();
    }

    /**
     * Whole months left of cover after the event. On a total loss the policy is extinguished by
     * the loss, so the premium still to fall due for the rest of the term comes out of the
     * indemnity instead of being collected month by month.
     *
     * <p>Zero when either date is missing: charging the insured for instalments nobody could count
     * is the wrong way to be wrong.
     */
    private int pendingInstallments(Case caseRecord, PolicySnapshot snapshot) {
        if (snapshot == null || snapshot.getEffectiveTo() == null || caseRecord.getOccurredAt() == null) {
            return 0;
        }
        LocalDate event = caseRecord.getOccurredAt().toLocalDate();
        LocalDate coverEnd = snapshot.getEffectiveTo().atZone(ZoneId.systemDefault()).toLocalDate();
        long months = ChronoUnit.MONTHS.between(event, coverEnd);
        return months <= 0 ? 0 : (int) months;
    }

    private BigDecimal percentageOf(BigDecimal amount, BigDecimal percentagePoints) {
        if (amount == null || percentagePoints == null) {
            return BigDecimal.ZERO;
        }
        return money(amount.multiply(percentagePoints).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
