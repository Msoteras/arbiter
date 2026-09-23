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
 * Coverage a policy grants. {@code branchId} is a logical reference to the common-schema
 * {@code Branch}, with no FK. {@code deductible} is in percentage points (10.00 = 10%), not a
 * 0..1 fraction.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_formula", nullable = false, length = 20)
    @Builder.Default
    private SettlementFormula settlementFormula = SettlementFormula.TOTAL_LOSS;

    /** Only meaningful under {@link SettlementFormula#TOTAL_LOSS}: on a repair the ceiling is the quote. */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_basis", nullable = false, length = 30)
    @Builder.Default
    private SettlementBasis settlementBasis = SettlementBasis.SUM_INSURED;

    /**
     * Percentage of the ceiling payable from the second event of the year on, in percentage points
     * (50.00 = 50%). Null means the event number doesn't reduce anything.
     */
    @Column(name = "second_event_percentage")
    private BigDecimal secondEventPercentage;

    /** Future premium: a total loss extinguishes the policy, so the rest of the year's premium is deducted. */
    @Column(name = "deduct_pending_installments", nullable = false)
    private boolean deductPendingInstallments;

    /**
     * Unpaid premium already due (clause 102, art. 5). Separate from
     * {@link #deductPendingInstallments} because it's a different debt with a different basis.
     */
    @Column(name = "deduct_overdue_balance", nullable = false)
    private boolean deductOverdueBalance;
}
