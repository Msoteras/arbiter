package ar.edu.utn.frba.arbiter.rules.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * {@code coverageId} points at cases-service's coverage: same schema, another owner, so no foreign
 * key. No insurer column: the schema already says whose rule it is.
 */
@Entity
@Table(name = "insurer_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    private String configuration;

    @Column(nullable = false)
    private String name;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    /** APROBAR | RECHAZAR | DERIVAR, over Classification. */
    @Column(length = 20)
    private String effect;

    private Integer priority;

    @Column(name = "blocks_fast_track", nullable = false)
    private boolean blocksFastTrack;

    /** Null together with {@code coverageId}: an insurer-wide rule (see {@code RuleType#insurerScoped()}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "coverage_id")
    private Long coverageId;

    /** A user, not a referente, so the author isn't tied to who may configure rules today. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
