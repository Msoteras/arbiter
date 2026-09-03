package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
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
 * How much gets paid on a claim, and where that number came from ("liquidación" in the insurer's
 * own vocabulary). The step between the analyst approving and the company paying: their procedure
 * (NSIN001 §5.2.1.2) treats determining the amount as its own act, and until this existed an
 * Arbiter case reached APPROVED without ever saying how much.
 *
 * <p><b>Inputs are stored next to the result, not just the result.</b> Redoing the arithmetic
 * months later has to land on the same number even though the policy, the coverage and the
 * referente's parameters have all moved on since. It's the same requirement Disposición SSN
 * 2/2023 already puts on {@code case_classification}, applied to the money.
 *
 * <p>{@link #calculatedAmount} and {@link #settledAmount} are deliberately separate:
 * the first is what the formula produced, the second what the analyst authorized. Letting the
 * adjustment overwrite the proposal would erase the very thing worth auditing — that a human
 * moved the number, and by how much. Whenever they differ, {@link #adjustmentReason} is required
 * (enforced by {@code SettlementService}: it's a rule about two columns, not about one).
 *
 * <p>A row exists once an analyst confirmed the amount, one per case — which is not the same as
 * the claim being resolved: over the branch's attribution ceiling the row sits in
 * {@link SettlementStatus#PENDING_AUTHORIZATION} until the referente signs. The proposal they see
 * beforehand isn't persisted: it's deterministic from inputs that are already frozen, so
 * recomputing it costs nothing and storing it would be a second truth that can go stale.
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

    /**
     * Which formula produced the amount. Only total loss today; repair after an attempted robbery
     * is a different one — it pays a repair quote, doesn't extinguish the policy and therefore
     * doesn't deduct the installments still to fall due — and enters here as a second literal.
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String formula = "TOTAL_LOSS";

    // ─── Frozen inputs ──────────────────────────────────────────────────────────

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_basis", nullable = false, length = 30)
    private SettlementBasis settlementBasis;

    /**
     * The replacement value the analyst accredited from the file (invoice, ticket, quote). Null
     * until they record one — and with {@link SettlementBasis#SUM_INSURED} it never caps anything,
     * it's evidence rather than a term of the contract.
     */
    @Column(name = "replacement_value")
    private BigDecimal replacementValue;

    /** Franchise in percentage points (10.00 = 10%), copied off the coverage at calculation time. */
    @Column(name = "deductible_rate")
    private BigDecimal deductibleRate;

    /** Which event of the year this is; 1 is the first. */
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

    /** What the formula produced. Never overwritten by the analyst's adjustment. */
    @Column(name = "calculated_amount", nullable = false)
    private BigDecimal calculatedAmount;

    /** What the analyst authorized. */
    @Column(name = "settled_amount", nullable = false)
    private BigDecimal settledAmount;

    /** Required whenever {@link #settledAmount} differs from {@link #calculatedAmount}. */
    @Column(name = "adjustment_reason", columnDefinition = "TEXT")
    private String adjustmentReason;

    // ─── Attributions (Anexo II) ────────────────────────────────────────────────

    /**
     * Which stage of the authorization chain this is at. The <b>settlement's</b> state, not the
     * case's: a case waiting on the referente stays in {@code PENDING_ANALYST_REVIEW}, because the
     * wait is an internal control the insured has no reason to watch go by.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.AUTHORIZED;

    /**
     * The branch ceiling in force when this was confirmed, frozen like every other input: the
     * referente can move it tomorrow, and this settlement still has to explain itself with the
     * limit that actually sent it for authorization. Null when the branch had no ceiling.
     */
    @Column(name = "authority_limit")
    private BigDecimal authorityLimit;

    /**
     * Who signed off above the ceiling. Null when the amount fell within the analyst's own
     * attribution — there is no second signer to record. Points at {@code arbiter_common.users}
     * rather than {@code insurer_referent}: the platform identity is the one cases-service knows.
     */
    @Column(name = "authorized_by_user_id")
    private Long authorizedByUserId;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    /** Why the referente sent it back. Lives until the analyst confirms again. */
    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    /**
     * The analyst's stated reason, held while the settlement waits for authorization. Not a copy
     * of {@code case_classification.analyst_justification}: that row doesn't exist yet. An
     * approval that needs the referente is <b>not</b> recorded as a decision until they clear it,
     * so the case never carries a verdict that took no effect and a return doesn't leave two
     * decisions on one claim. Cleared once the decision is forwarded.
     */
    @Column(name = "pending_justification", columnDefinition = "TEXT")
    private String pendingJustification;

    // ─── Traceability ───────────────────────────────────────────────────────────

    @Column(name = "coverage_id", nullable = false)
    private Long coverageId;

    @Column(name = "policy_snapshot_id")
    private Long policySnapshotId;

    /** Who authorized the amount. */
    @Column(name = "analyst_id", nullable = false)
    private Long analystId;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
}
