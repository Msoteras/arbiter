package ar.edu.utn.frba.arbiter.auth.models.entities;

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
 * That an insured has a relationship with an insurer ("acuerdo" in the DER). Given a
 * surrogate id — the DER draws it as (aseguradora_id, asegurado_id) with neither marked
 * PK/FK, nothing to reference otherwise. Nothing in the DER points at this table today
 * (Policy links to Insured directly, not through here) — kept anyway since it's
 * explicitly drawn. Unlike {@code rama_aseguradora} (removed, see InsurerClaimCause's
 * Javadoc), this one wasn't flagged as unneeded — leave it unless the team says
 * otherwise.
 */
@Entity
@Table(name = "insurer_agreement", uniqueConstraints = @UniqueConstraint(columnNames = {"insured_id", "insurer_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insured_id", nullable = false)
    private Insured insured;

    /** Logical reference to rules-service's Insurer — cross-module, no real FK. */
    @Column(name = "insurer_id", nullable = false)
    private Long insurerId;
}
