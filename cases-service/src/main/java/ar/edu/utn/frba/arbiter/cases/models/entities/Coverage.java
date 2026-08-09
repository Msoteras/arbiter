package ar.edu.utn.frba.arbiter.cases.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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
 * deductible, per-policy limits. Today this data comes live from
 * {@code InsurerAdapter}/{@code MockInsurerAdapter} as part of {@code PolicyResponse}, not
 * from a local table; this is the target shape for persisting it, unused for now.
 * {@code branchId} is a logical reference to rules-service's Branch — cross-module, no FK.
 *
 * <p>{@code deductible} is stored in percentage points (10.00 = 10%), not a 0..1 fraction —
 * matches the existing seed data. {@code clause} and {@code exclusions} have no backing table
 * in the DER (docs/decisiones-reglas-a-validar.md, D3/D7): parked here in JSONB until the
 * rules engine actually consumes them.
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
}
