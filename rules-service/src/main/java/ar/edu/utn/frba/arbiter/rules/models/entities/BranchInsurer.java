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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which branches an insurer has enabled ("rama_aseguradora" in the DER). Modeled with its
 * own surrogate id — not a plain composite-PK join table — because
 * {@link InsurerClaimCause#getBranchInsurer()} needs a single column to reference; the DER
 * draws rama_aseguradora as (aseguradora_id, rama_id) only, with no id, which doesn't give
 * hecho_generador_aseguradora anything to point at. The unique constraint preserves the
 * DER's intended composite key.
 */
@Entity
@Table(name = "branch_insurer", uniqueConstraints = @UniqueConstraint(columnNames = {"insurer_id", "branch_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchInsurer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insurer_id", nullable = false)
    private Insurer insurer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;
}
