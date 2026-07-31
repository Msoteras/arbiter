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
 * Required document type per branch + claim cause (+ risk band) — "requisito_documental"
 * in the DER, the configurable target shape of CLAUDE.md's "AgendaDocumental". Today the
 * equivalent list is hardcoded in MockRulesAdapter.requiredDocumentTypes; this table
 * doesn't replace that wiring yet.
 */
@Entity
@Table(name = "document_requirement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LOW | MEDIUM | HIGH | CRITICAL, nullable — not every requirement is risk-scoped. */
    @Column(name = "risk_band", length = 20)
    private String riskBand;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_cause_id", nullable = false)
    private ClaimCause claimCause;
}
