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
 * Append-only audit trail of changes to a {@link ScoringConfiguration}
 * ("historial_configuracion_scoring" in the DER). {@code changedBy} is a logical
 * reference to a user (auth-service, another module) — not a real FK.
 */
@Entity
@Table(name = "scoring_configuration_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoringConfigurationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_config", nullable = false, columnDefinition = "TEXT")
    private String snapshotConfig;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scoring_configuration_id", nullable = false)
    private ScoringConfiguration scoringConfiguration;
}
