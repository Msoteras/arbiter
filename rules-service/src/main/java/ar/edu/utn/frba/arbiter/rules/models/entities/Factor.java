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

/**
 * Risk-scoring factor catalog (e.g. amount_ratio, claim_frequency — see
 * classification-service's RiskFactorIds, which is where these are actually evaluated).
 */
@Entity
@Table(name = "factor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Factor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;
}
