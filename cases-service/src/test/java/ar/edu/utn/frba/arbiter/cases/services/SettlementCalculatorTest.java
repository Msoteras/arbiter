package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formula, checked against the documents it was transcribed from. The first test is the worked
 * example printed in BBVA's Celulares manual, numbers and all: if that one ever stops passing, the
 * calculator stopped agreeing with the product it implements.
 */
class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    /**
     * "Pago en caso de Siniestro", manual Seguro de Celular: sum insured $300.000, 10% franchise
     * ($30.000), event six months before the end of the term with $35.000 of instalments left to
     * fall due, indemnity $235.000.
     */
    @Test
    void reproducesTheWorkedExampleFromTheCelularesManual() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, true, false);
        PolicySnapshot snapshot = snapshot("300000.00", LocalDate.of(2024, 1, 1), "5000.00", null, 1);
        Case claim = claim(LocalDateTime.of(2023, 6, 1, 10, 0));

        CaseSettlement settlement = calculator.calculate(
                claim, coverage, null, snapshot, null);

        assertThat(settlement.getDeductibleAmount()).isEqualByComparingTo("30000.00");
        assertThat(settlement.getPendingInstallments()).isEqualTo(7);
        assertThat(settlement.getPendingInstallmentsAmount()).isEqualByComparingTo("35000.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("235000.00");
    }

    /**
     * Article 7, Bases de Indemnización: the insurer's liability doesn't exceed the lesser of the
     * sum insured and the cost of replacing the item.
     */
    @Test
    void takesTheReplacementValueWhenItIsBelowTheSumInsured() {
        Coverage coverage = coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", null, false, false);
        PolicySnapshot snapshot = snapshot("1000000.00", LocalDate.of(2027, 1, 1), null, null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, new BigDecimal("600000.00"));

        // 600.000 de techo, menos 100.000 de franquicia (10% de la SUMA ASEGURADA, no del techo).
        assertThat(settlement.getDeductibleAmount()).isEqualByComparingTo("100000.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("500000.00");
    }

    /** A ceiling can't be lowered by a number nobody produced. */
    @Test
    void fallsBackToTheSumInsuredWhenNoReplacementValueWasAccredited() {
        Coverage coverage = coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", null, false, false);
        PolicySnapshot snapshot = snapshot("1000000.00", LocalDate.of(2027, 1, 1), null, null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("900000.00");
    }

    /** A replacement value above the sum insured doesn't raise anything — the sum insured is a cap. */
    @Test
    void ignoresAReplacementValueAboveTheSumInsured() {
        Coverage coverage = coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", null, false, false);
        PolicySnapshot snapshot = snapshot("1000000.00", LocalDate.of(2027, 1, 1), null, null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, new BigDecimal("1500000.00"));

        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("900000.00");
    }

    /**
     * Tecnología Portátil particular conditions: "dos eventos por año, primer evento hasta un 100%
     * de la suma asegurada, segundo hasta un 50%". The franchise stays a percentage of the sum
     * insured, so it does not halve along with the ceiling.
     */
    @Test
    void paysTheSecondEventOfTheYearAtItsReducedPercentage() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", "50.00", false, false);
        PolicySnapshot snapshot = snapshot("800000.00", LocalDate.of(2027, 1, 1), null, null, 2);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getEventOrdinal()).isEqualTo(2);
        assertThat(settlement.getEventPercentage()).isEqualByComparingTo("50.00");
        // 400.000 de techo − 80.000 de franquicia (10% de 800.000).
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("320000.00");
    }

    /** With no reduced rate configured, being the second event changes nothing. */
    @Test
    void doesNotReduceASecondEventWhenTheCoverageSetsNoRate() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, false, false);
        PolicySnapshot snapshot = snapshot("800000.00", LocalDate.of(2027, 1, 1), null, null, 2);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getEventPercentage()).isEqualByComparingTo("100.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("720000.00");
    }

    /** Clause 102, article 5: arrears already due come off the indemnity — when turned on. */
    @Test
    void deductsTheOverdueBalanceOnlyWhenTheCoverageSaysSo() {
        PolicySnapshot snapshot = snapshot("500000.00", LocalDate.of(2027, 1, 1), null, "45000.00", 1);
        Case claim = claim(LocalDateTime.of(2026, 6, 1, 10, 0));

        CaseSettlement off = calculator.calculate(
                claim, coverage(SettlementBasis.SUM_INSURED, "10.00", null, false, false), null, snapshot, null);
        CaseSettlement on = calculator.calculate(
                claim, coverage(SettlementBasis.SUM_INSURED, "10.00", null, false, true), null, snapshot, null);

        assertThat(off.getOverdueBalanceAmount()).isEqualByComparingTo("0.00");
        assertThat(off.getCalculatedAmount()).isEqualByComparingTo("450000.00");
        assertThat(on.getOverdueBalanceAmount()).isEqualByComparingTo("45000.00");
        assertThat(on.getCalculatedAmount()).isEqualByComparingTo("405000.00");
    }

    /**
     * Deductions bigger than the ceiling mean the insured is owed nothing, not that they owe the
     * company: whatever is left is a debt of the policy, and collecting it is not this claim's job.
     */
    @Test
    void neverGoesBelowZero() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, false, true);
        PolicySnapshot snapshot = snapshot("100000.00", LocalDate.of(2027, 1, 1), null, "500000.00", 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * A coverage that deducts instalments but a snapshot that doesn't know what one costs deducts
     * nothing. The analyst is told so by {@code SettlementService.warnings}; charging them for
     * instalments nobody could count is the wrong way to be wrong.
     */
    @Test
    void deductsNoInstallmentsWhenTheSnapshotDoesNotCarryTheirAmount() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, true, false);
        PolicySnapshot snapshot = snapshot("500000.00", LocalDate.of(2027, 1, 1), null, null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getPendingInstallmentsAmount()).isEqualByComparingTo("0.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("450000.00");
    }

    /** An event after the term ends can't leave instalments to fall due. */
    @Test
    void countsNoInstallmentsForAnEventAfterTheTermEnds() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, true, false);
        PolicySnapshot snapshot = snapshot("500000.00", LocalDate.of(2026, 1, 1), "5000.00", null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, null);

        assertThat(settlement.getPendingInstallments()).isZero();
        assertThat(settlement.getPendingInstallmentsAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * Cases filed before the snapshot existed still have to be settleable — falling back to the
     * synced coverage beats refusing to put a number on them. It reads {@code policy_coverage} and
     * not the policy because a policy has no single sum insured: it covers robo and hurto with a
     * different amount each.
     */
    @Test
    void fallsBackToThePolicyCoverageWhenThereIsNoSnapshot() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, true, true);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage,
                policyCoverage("1300000.00", null), null, null);

        assertThat(settlement.getSumInsured()).isEqualByComparingTo("1300000.00");
        assertThat(settlement.getPendingInstallmentsAmount()).isEqualByComparingTo("0.00");
        assertThat(settlement.getOverdueBalanceAmount()).isEqualByComparingTo("0.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("1170000.00");
    }

    /**
     * Cuando la póliza trae su propia franquicia, esa gana sobre la que el referente configuró en
     * la cobertura: la de la cobertura es el default de la compañía para ese riesgo, la de la
     * póliza es la que se escribió en ESTE contrato. Se le descuenta a un asegurado concreto, no
     * a un promedio.
     */
    @Test
    void thePolicyOwnFranchiseWinsOverTheCoverageDefault() {
        Coverage coverage = coverage(SettlementBasis.SUM_INSURED, "10.00", null, false, false);
        PolicySnapshot snapshot = snapshot("800000.00", LocalDate.of(2027, 1, 1), null, null, 1);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage,
                policyCoverage("800000.00", "20.00"), snapshot, null);

        assertThat(settlement.getDeductibleRate()).isEqualByComparingTo("20.00");
        assertThat(settlement.getDeductibleAmount()).isEqualByComparingTo("160000.00");
        assertThat(settlement.getCalculatedAmount()).isEqualByComparingTo("640000.00");
    }

    /** Every input the sheet was built from is frozen on the row, not just the result. */
    @Test
    void freezesTheInputsAlongsideTheResult() {
        Coverage coverage = coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", "50.00", true, true);
        PolicySnapshot snapshot = snapshot("800000.00", LocalDate.of(2027, 1, 1), "16000.00", "32000.00", 2);

        CaseSettlement settlement = calculator.calculate(
                claim(LocalDateTime.of(2026, 6, 1, 10, 0)), coverage, null, snapshot, new BigDecimal("700000.00"));

        assertThat(settlement.getFormula()).isEqualTo(SettlementCalculator.TOTAL_LOSS);
        assertThat(settlement.getSettlementBasis()).isEqualTo(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT);
        assertThat(settlement.getSumInsured()).isEqualByComparingTo("800000.00");
        assertThat(settlement.getReplacementValue()).isEqualByComparingTo("700000.00");
        assertThat(settlement.getDeductibleRate()).isEqualByComparingTo("10.00");
        assertThat(settlement.getInstallmentAmount()).isEqualByComparingTo("16000.00");
        assertThat(settlement.getCoverageId()).isEqualTo(42L);
        assertThat(settlement.getPolicySnapshotId()).isEqualTo(99L);
        assertThat(settlement.getCalculatedAt()).isNotNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private Coverage coverage(SettlementBasis basis, String deductible, String secondEventPercentage,
                              boolean deductInstallments, boolean deductOverdue) {
        return Coverage.builder()
                .id(42L)
                .name("Robo de celular")
                .settlementBasis(basis)
                .deductible(new BigDecimal(deductible))
                .secondEventPercentage(secondEventPercentage == null ? null : new BigDecimal(secondEventPercentage))
                .deductPendingInstallments(deductInstallments)
                .deductOverdueBalance(deductOverdue)
                .build();
    }

    private PolicySnapshot snapshot(String sumInsured, LocalDate effectiveTo, String installmentAmount,
                                    String overdueBalance, int eventsInYear) {
        return PolicySnapshot.builder()
                .id(99L)
                .externalPolicyNumber("POL-CEL-2026-042")
                .sumInsured(new BigDecimal(sumInsured))
                .effectiveTo(effectiveTo.atStartOfDay(ZoneId.systemDefault()).toInstant())
                .installmentAmount(installmentAmount == null ? null : new BigDecimal(installmentAmount))
                .overdueBalance(overdueBalance == null ? null : new BigDecimal(overdueBalance))
                .eventsInYear(eventsInYear)
                .queriedAt(Instant.now())
                .build();
    }

    private Case claim(LocalDateTime occurredAt) {
        return Case.builder()
                .id(1L)
                .occurredAt(occurredAt)
                .policy(Policy.builder().id(1L).build())
                .build();
    }

    /** Los términos que la póliza contrató para esta cobertura: su suma y su franquicia. */
    private PolicyCoverage policyCoverage(String sumInsured, String deductiblePct) {
        return PolicyCoverage.builder()
                .policyId(1L)
                .displayOrder(1)
                .sumInsured(new BigDecimal(sumInsured))
                .deductiblePct(deductiblePct == null ? null : new BigDecimal(deductiblePct))
                .build();
    }
}
