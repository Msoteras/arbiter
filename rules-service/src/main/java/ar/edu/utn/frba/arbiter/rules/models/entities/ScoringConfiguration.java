package ar.edu.utn.frba.arbiter.rules.models.entities;

import jakarta.persistence.Column;
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

import java.time.Instant;

/**
 * Named set of factor weights + score bands ("configuracion_scoring" in the DER) — the
 * data-driven config that classification-service's RiskScoringService already expects
 * to receive as {@code BusinessRules.ScoringConfig} (currently served by MockRulesAdapter).
 *
 * <p>A single row per insurer (per-tenant table, no branch column): every branch shares the same
 * scoring, unlike Fast Track and the document schedule.
 */
@Entity
@Table(name = "scoring_configuration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoringConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /**
     * Whether this insurer's Fast Track claims still go through the heavy analysis (OCR of every
     * attachment + the image fraud cascade) so their score comes out complete, instead of only from
     * the structured-data factors. Defaults to false: Fast Track stays fast. It doesn't veto Fast
     * Track — the score is a parallel signal; it only decides how much analysis runs.
     */
    @Column(name = "full_analysis_on_fast_track", nullable = false)
    private boolean fullAnalysisOnFastTrack;
}
