package ar.edu.utn.frba.arbiter.rules.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
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
 * in the DER, CLAUDE.md's "AgendaDocumental".
 *
 * <p>This table is what the missing-documentation gate actually reads: the referente edits it
 * through {@code PUT /api/v1/rules/document-requirements}, classification-service pulls it over
 * {@code GET /api/v1/rules/document-requirements/internal}
 * ({@code DocumentRequirementController.internalByCoverage}) via
 * {@code RulesRestAdapter.overlayDocumentRequirements}, and
 * {@code ClassificationOrchestrator.checkRequiredDocuments} gates on the result.
 * {@code MockRulesAdapter.requiredDocumentTypes} is only the baseline used when the referente
 * configured nothing for that coverage + claim cause — the same fallback pattern as Fast Track
 * and the coverage exclusions.
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
