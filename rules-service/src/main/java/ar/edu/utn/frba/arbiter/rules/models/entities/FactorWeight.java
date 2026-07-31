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
 * How much a factor weighs within a {@link ScoringConfiguration} ("peso_factor" in the
 * DER). {@code factorCode} matches classification-service's RiskFactorIds constants
 * (e.g. "amount_ratio") by string, not by FK to {@link Factor} — same loose-coupling
 * as the DER draws it.
 */
@Entity
@Table(name = "factor_weight")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FactorWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factor_code", nullable = false)
    private String factorCode;

    @Column(nullable = false)
    private BigDecimal weight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scoring_configuration_id", nullable = false)
    private ScoringConfiguration scoringConfiguration;
}
