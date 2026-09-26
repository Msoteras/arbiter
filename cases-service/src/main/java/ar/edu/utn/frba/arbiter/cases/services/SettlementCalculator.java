package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * What a claim should pay, from the case, the configured coverage and the frozen policy snapshot, per
 * the insurer's product manuals:
 *
 * <pre>
 *   TOTAL LOSS — the item is gone
 *   ceiling               = sum insured (or the lesser of it and the replacement value)
 *   event cap             = ceiling × event %          (2nd event of the year → 50%)
 *   − deductible          = sum insured × deductible %
 *   − pending instalments = instalments left × instalment amount
 *   − overdue balance     = unpaid balance of the contract
 *   = amount payable      (never negative)
 *
 *   REPAIR — the item was damaged
 *   ceiling               = accredited quote, capped at the sum insured
 *   event cap             = ceiling × event %
 *   − deductible          = sum insured × deductible %
 *   − overdue balance     = unpaid balance of the contract
 *   = amount payable      (never negative)
 * </pre>
 *
 * <p>Pending instalments only on a total loss, which extinguishes the contract. The deductible is a
 * percentage of the sum insured, so it doesn't shrink on a second event. This only proposes, and the
 * risk score plays no part: a suspicious claim is rejected or referred, not quietly paid less.
 */
@Service
public class SettlementCalculator {

    /** Money is rounded to cents at every step, the way the settlement sheet is read. */
    private static final int MONEY_SCALE = 2;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FULL_PERCENTAGE = new BigDecimal("100.00");

    /**
     * Not persisted: the caller decides whether this is a fresh row or overwrites the standing one.
     *
     * @param replacementValue what the analyst accredited, or null if nothing was recorded yet
     * @param formula          passed in rather than read off the coverage: a damage cover settles
     *                         by repair, but an item declared irreparable is a total loss
     */
    public CaseSettlement calculate(Case caseRecord, Coverage coverage, PolicyCoverage policyCoverage,
                                    PolicySnapshot snapshot, BigDecimal replacementValue,
                                    SettlementFormula formula) {
        BigDecimal sumInsured = sumInsured(policyCoverage, snapshot);
        SettlementBasis basis = coverage.getSettlementBasis() == null
                ? SettlementBasis.SUM_INSURED
                : coverage.getSettlementBasis();

        BigDecimal ceiling = ceiling(formula, basis, sumInsured, replacementValue);

        int eventOrdinal = eventOrdinal(snapshot);
        BigDecimal eventPercentage = eventPercentage(coverage, eventOrdinal);
        BigDecimal cappedAmount = percentageOf(ceiling, eventPercentage);

        BigDecimal deductibleAmount = percentageOf(sumInsured, deductibleRate(coverage, policyCoverage));

        // Total loss only, whatever the coverage switch says: a repair doesn't extinguish the policy.
        int pendingInstallments = formula == SettlementFormula.TOTAL_LOSS
                && coverage.isDeductPendingInstallments()
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
        // Deductions above the ceiling mean nothing is owed, not that the insured owes the insurer.
        if (calculated.signum() < 0) {
            calculated = BigDecimal.ZERO;
        }

        return CaseSettlement.builder()
                .caseId(caseRecord.getId())
                .formula(formula)
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
     * The snapshot first: it is what the insurer answered at filing, while {@code policy_coverage} keeps
     * being re-synced. A policy has no aggregate sum insured, hence the per-coverage fallback.
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

    /** The contract's own rate wins over the coverage's default: it may have been sold differently. */
    private BigDecimal deductibleRate(Coverage coverage, PolicyCoverage policyCoverage) {
        if (policyCoverage != null && policyCoverage.getDeductiblePct() != null) {
            return policyCoverage.getDeductiblePct();
        }
        return coverage.getDeductible();
    }

    /**
     * On a repair it is the accredited quote capped by the sum insured, and zero with no quote —
     * falling back to the sum insured would pay a whole phone for an uncosted broken screen. On a
     * total loss with nothing accredited it stays the sum insured the contract fixed.
     */
    private BigDecimal ceiling(SettlementFormula formula, SettlementBasis basis,
                               BigDecimal sumInsured, BigDecimal accreditedAmount) {
        boolean accredited = accreditedAmount != null && accreditedAmount.signum() > 0;

        if (formula == SettlementFormula.REPAIR) {
            return accredited ? sumInsured.min(money(accreditedAmount)) : BigDecimal.ZERO;
        }
        if (basis != SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT || !accredited) {
            return sumInsured;
        }
        return sumInsured.min(money(accreditedAmount));
    }

    /** Frozen by classification-service; 1 when the snapshot predates the column. */
    private int eventOrdinal(PolicySnapshot snapshot) {
        if (snapshot == null || snapshot.getEventsInYear() == null || snapshot.getEventsInYear() < 1) {
            return 1;
        }
        return snapshot.getEventsInYear();
    }

    /** 100% for the year's first event; from the second on, the coverage's reduced rate if configured. */
    private BigDecimal eventPercentage(Coverage coverage, int eventOrdinal) {
        if (eventOrdinal < 2 || coverage.getSecondEventPercentage() == null) {
            return FULL_PERCENTAGE;
        }
        return coverage.getSecondEventPercentage();
    }

    /**
     * Whole months of cover left after the event. Zero when either date is missing, rather than
     * charging for instalments nobody could count.
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
