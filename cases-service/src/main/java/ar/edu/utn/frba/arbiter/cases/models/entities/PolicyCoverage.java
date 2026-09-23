package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A coverage as contracted on one policy, with its own sum insured and deductible. Distinct from the
 * {@link Coverage} catalog, which holds the insurer-wide definition of the risk that the referente administers.
 */
@Entity
@Table(name = "policy_coverage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    /** EAGER: every reader needs it, and with open-in-view off a lazy proxy outside the session throws. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    /** The position the insurer lists it in. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    /** Percentage points (10.00 = 10%), same unit as {@code Coverage.deductible}. Nullable. */
    @Column(name = "deductible_pct")
    private BigDecimal deductiblePct;
}
