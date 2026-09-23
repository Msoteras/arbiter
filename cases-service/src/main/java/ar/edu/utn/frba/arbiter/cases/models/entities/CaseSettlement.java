package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inputs are frozen next to the result so the arithmetic can be redone later with the same outcome,
 * even after the policy or the referente's parameters change. {@link #calculatedAmount} and
 * {@link #settledAmount} are kept apart so a human adjustment stays auditable.
 *
 * <p>A row exists once the analyst confirms the amount; above the branch ceiling it waits in
 * {@link SettlementStatus#PENDING_AUTHORIZATION}. Proposals are never persisted: they are
 * deterministic from frozen inputs.
 */
@Entity
@Table(name = "case_settlement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SettlementFormula formula = SettlementFormula.TOTAL_LOSS;

    // ─── Frozen inputs ──────────────────────────────────────────────────────────

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_basis", nullable = false, length = 30)
    private SettlementBasis settlementBasis;

    /** With {@link SettlementBasis#SUM_INSURED} it never caps anything: it's evidence, not a contract term. */
    @Column(name = "replacement_value")
    private BigDecimal replacementValue;

    /** Percentage points (10.00 = 10%). */
    @Column(name = "deductible_rate")
    private BigDecimal deductibleRate;

    /** 1 is the first event of the year. */
    @Column(name = "event_ordinal", nullable = false)
    @Builder.Default
    private Integer eventOrdinal = 1;

    /** Percentage of the ceiling this event is worth (100.00, or the coverage's reduced rate). */
    @Column(name = "event_percentage", nullable = false)
    @Builder.Default
    private BigDecimal eventPercentage = new BigDecimal("100.00");

    @Column(name = "pending_installments", nullable = false)
    @Builder.Default
    private Integer pendingInstallments = 0;

    @Column(name = "installment_amount")
    private BigDecimal installmentAmount;

    // ─── Deductions applied ─────────────────────────────────────────────────────

    @Column(name = "deductible_amount", nullable = false)
    @Builder.Default
    private BigDecimal deductibleAmount = BigDecimal.ZERO;

    @Column(name = "pending_installments_amount", nullable = false)
    @Builder.Default
    private BigDecimal pendingInstallmentsAmount = BigDecimal.ZERO;

    @Column(name = "overdue_balance_amount", nullable = false)
    @Builder.Default
    private BigDecimal overdueBalanceAmount = BigDecimal.ZERO;

    // ─── Result ─────────────────────────────────────────────────────────────────

    /** Never overwritten by the analyst's adjustment. */
    @Column(name = "calculated_amount", nullable = false)
    private BigDecimal calculatedAmount;

    @Column(name = "settled_amount", nullable = false)
    private BigDecimal settledAmount;

    /** Required whenever {@link #settledAmount} differs from {@link #calculatedAmount}. */
    @Column(name = "adjustment_reason", columnDefinition = "TEXT")
    private String adjustmentReason;

    // ─── Authorization ──────────────────────────────────────────────────────────

    /**
     * The settlement's state, not the case's: a case waiting on the referente stays in
     * {@code PENDING_ANALYST_REVIEW}, since that internal control is invisible to the insured.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.AUTHORIZED;

    /** The ceiling in force when confirmed. Null when the branch had no ceiling. */
    @Column(name = "authority_limit")
    private BigDecimal authorityLimit;

    /** Null within the analyst's own limit. Points at {@code arbiter_common.users}, not {@code insurer_referent}. */
    @Column(name = "authorized_by_user_id")
    private Long authorizedByUserId;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    /** Lives until the analyst confirms again. */
    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    /**
     * Held while waiting for authorization: an approval that needs the referente isn't recorded as a
     * decision until they clear it. Cleared once the decision is forwarded.
     */
    @Column(name = "pending_justification", columnDefinition = "TEXT")
    private String pendingJustification;

    // ─── Traceability ───────────────────────────────────────────────────────────

    @Column(name = "coverage_id", nullable = false)
    private Long coverageId;

    @Column(name = "policy_snapshot_id")
    private Long policySnapshotId;

    @Column(name = "analyst_id", nullable = false)
    private Long analystId;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
}
