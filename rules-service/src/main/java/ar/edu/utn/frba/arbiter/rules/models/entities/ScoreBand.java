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

import java.math.BigDecimal;

/**
 * Score threshold that maps a normalized risk score to a {@code RiskBand}
 * ("configuracion_banda" in the DER) — same shape as
 * {@code BusinessRules.ScoringConfig.Band} in classification-service.
 */
@Entity
@Table(name = "score_band")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoreBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LOW | MEDIUM | HIGH | CRITICAL — mirrors classification-service's RiskBand enum by name. */
    @Column(nullable = false, length = 20)
    private String band;

    @Column(name = "min_score_inclusive", nullable = false)
    private BigDecimal minScoreInclusive;

    @Column(nullable = false)
    private boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scoring_configuration_id", nullable = false)
    private ScoringConfiguration scoringConfiguration;
}
