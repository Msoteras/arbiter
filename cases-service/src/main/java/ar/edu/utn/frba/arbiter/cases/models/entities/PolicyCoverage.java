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
 * One coverage as contracted on one policy — "cobertura_contratada". Local mirror of
 * {@code aseguradora.cobertura}: a policy has several of these ({@code cobertura1..cobertura20} in
 * the source), each with its own sum insured and deductible.
 *
 * <p><b>Why this is not the {@link Coverage} catalog.</b> {@code coverage} holds the
 * <i>definition</i> of a risk — waiting period, events cap, reporting deadline, whether it reaches
 * the family group — which the referente administers and is the same across the whole insurer.
 * This holds what a <i>particular contract</i> bought, which the company sets. Putting the sum
 * insured on the catalog would need one catalog row per policy; putting it on the policy (where it
 * used to be) needs one policy per coverage, which is what made
 * {@code PolicySynchronizer} keep only the first coverage and drop the rest.
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

    /**
     * EAGER, unlike most associations here: nothing ever reads a contracted coverage without
     * needing what it is (its name to resolve the claim cause, its terms to evaluate the rules),
     * and with open-in-view off a lazy proxy resolved outside the session throws.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    /** {@code orden} in the source — the position the company lists it in (cobertura1, cobertura2…). */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    /** Percentage points (10.00 = 10%), same unit as {@code Coverage.deductible}. Nullable. */
    @Column(name = "deductible_pct")
    private BigDecimal deductiblePct;
}
