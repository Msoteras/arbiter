package ar.edu.utn.frba.arbiter.rules.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
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
 * Valid claim-cause combination an insurer has enabled for a branch
 * ("hecho_generador_aseguradora" in the DER). Points at {@link Insurer} and
 * {@link Branch} directly — the DER's own draft went through an intermediate
 * "rama_aseguradora" entity, but that's not needed for how multi-tenant actually works
 * here: same reasoning {@link InsurerRule} already follows
 * with its own direct {@code branch} field. {@code coverageId} is a logical reference to
 * cases-service's Coverage — cross-module, no real FK.
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
    @JoinColumn(name = "insurer_id", nullable = false)
    private Insurer insurer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_cause_id", nullable = false)
    private ClaimCause claimCause;

    @Column(name = "coverage_id")
    private Long coverageId;
}
