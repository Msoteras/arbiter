package ar.edu.utn.frba.arbiter.common.models.entities.tenant;

import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coverage a policy grants ("Cobertura" in CLAUDE.md's domain vocabulary) — sum insured,
 * deductible, per-policy limits. {@code branchId} is a logical reference to the common-schema
 * Branch — cross-module, no FK.
 *
 * <p>Shared here (and not owned by cases-service) because rules-service also needs it: the rule
 * texts the referente writes are stored per branch, but the classification engine only carries a
 * {@code coverageId} — branch and claim cause reach it as names, not ids. So resolving
 * coverage → branch is what lets the engine ask for the texts that apply to a claim.
 *
 * <p>{@code deductible} is stored in percentage points (10.00 = 10%), not a 0..1 fraction —
 * matches the existing seed data. {@code clause} and {@code exclusions} have no backing table
 * in the DER: parked here in JSONB.
 */
@Entity
@Table(name = "coverage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Coverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "report_deadline_hours")
    private Long reportDeadlineHours;

    @Column(name = "max_events_per_year")
    private Integer maxEventsPerYear;

    @Column(name = "covers_family_group", nullable = false)
    private boolean coversFamilyGroup;

    private BigDecimal deductible;

    @Column(name = "claim_exhausts_coverage", nullable = false)
    private boolean claimExhaustsCoverage;

    @Column(name = "is_individual", nullable = false)
    private boolean individual;

    @Column(name = "waiting_period_days")
    private Integer waitingPeriodDays;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(length = 20)
    private String clause;

    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> exclusions;

    /**
     * How a claim on this coverage is settled, which follows from what happens to the item: a
     * total loss extinguishes the policy, a repair doesn't. Never null in the DB (defaults to
     * {@code TOTAL_LOSS}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_formula", nullable = false, length = 20)
    @Builder.Default
    private SettlementFormula settlementFormula = SettlementFormula.TOTAL_LOSS;

    /**
     * How the indemnity ceiling is worked out. Never null in the DB (defaults to
     * {@code SUM_INSURED}); {@code SettlementCalculator} still treats a null as that default, so a
     * row written before this column existed doesn't sink a settlement.
     *
     * <p>Only meaningful under {@link SettlementFormula#TOTAL_LOSS}: on a repair the ceiling is
     * the quote, and there is no other basis to choose.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_basis", nullable = false, length = 30)
    @Builder.Default
    private SettlementBasis settlementBasis = SettlementBasis.SUM_INSURED;

    /**
     * Percentage of the ceiling payable from the second event of the year onwards, in percentage
     * points like {@link #deductible} (50.00 = 50%). Null means the event number doesn't reduce
     * anything, which is the Celulares case — that product allows a single event per year, so
     * there is no second one to price.
     */
    @Column(name = "second_event_percentage")
    private BigDecimal secondEventPercentage;

    /**
     * Whether the premium installments still to fall due get deducted. True for total loss on
     * these products: the policy is extinguished by the loss, so the rest of the year's premium
     * comes out of the indemnity ("menos las cuotas pendientes de pago", Celulares manual).
     */
    @Column(name = "deduct_pending_installments", nullable = false)
    private boolean deductPendingInstallments;

    /**
     * Whether arrears already due get deducted. Clause 102, article 5: "aprobada la liquidación de
     * un siniestro el Asegurador podrá descontar de la indemnización cualquier saldo o deuda
     * vencida de este contrato". Separate from {@link #deductPendingInstallments} because it's a
     * different debt with a different basis — one is future premium, this one is unpaid premium.
     */
    @Column(name = "deduct_overdue_balance", nullable = false)
    private boolean deductOverdueBalance;
}
