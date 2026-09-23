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
 * Named set of factor weights + score bands, served to classification-service as
 * {@code BusinessRules.ScoringConfig}. A single row per insurer: every branch shares the same
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
     * Whether Fast Track claims still run the heavy analysis (OCR + image fraud) so their score
     * comes out complete. It never vetoes Fast Track; it only decides how much analysis runs.
     */
    @Column(name = "full_analysis_on_fast_track", nullable = false)
    private boolean fullAnalysisOnFastTrack;
}
