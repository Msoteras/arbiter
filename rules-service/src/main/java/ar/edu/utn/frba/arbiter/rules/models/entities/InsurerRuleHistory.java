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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Append-only audit trail of changes to an {@link InsurerRule} ("historial_regla_aseguradora"
 * in the DER). {@code changedBy} is a logical reference to a user (auth-service, another
 * module) — not a real FK, same criterion used across the rest of the schema.
 */
@Entity
@Table(name = "insurer_rule_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerRuleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_version", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String configVersion;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    private String reason;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    // Column is rule_id in the real DDL — the DER's original name for it, kept even though
    // the FK target is insurer_rule (there's no common "rule" table to point at instead;
    // see README-multitenant.md).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private InsurerRule insurerRule;

    @Column(name = "changed_by")
    private Long changedBy;
}
