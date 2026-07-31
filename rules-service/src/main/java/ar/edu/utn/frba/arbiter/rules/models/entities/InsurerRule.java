package ar.edu.utn.frba.arbiter.rules.models.entities;

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

import java.time.Instant;

/**
 * How an insurer customizes a base {@link Rule} ("regla_aseguradora" in the DER) —
 * effect, priority, and whether it blocks Fast Track. {@code coverageId} is a logical
 * reference, same reason as {@link InsurerClaimCause#getCoverageId()}.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insurer_id", nullable = false)
    private Insurer insurer;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(columnDefinition = "TEXT")
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "coverage_id")
    private Long coverageId;
}
