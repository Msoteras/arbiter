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

/**
 * Valid claim-cause + coverage combination for a given {@link BranchInsurer}
 * ("hecho_generador_aseguradora" in the DER). {@code coverageId} is a logical reference —
 * Coverage isn't modeled yet (pending the Poliza/Cobertura/BienAsegurado/Asegurado work,
 * still mocked for now).
 */
@Entity
@Table(name = "insurer_claim_cause")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimCause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_insurer_id", nullable = false)
    private BranchInsurer branchInsurer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_cause_id", nullable = false)
    private ClaimCause claimCause;

    @Column(name = "coverage_id")
    private Long coverageId;
}
