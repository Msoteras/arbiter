package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The analyst's verdict on a case ("clasificacion_expediente" in the DER). Human-in-the-loop is
 * mandatory (decision #5): no case reaches a final state without a row here, and the model's
 * recommendation on the other side of {@link #llmAnalysis} is never binding.
 *
 * <p>It points at the analysis instead of copying it. The classification log this replaces
 * duplicated the whole snapshot — factors, forensic report, insured name — onto a second row
 * just so a later read would still find them; with the FK there is one copy and one truth.
 *
 * <p>{@code llmAnalysis} is nullable: a Fast Track case is still decided by an analyst, but
 * there was no model run to point at.
 */
@Entity
@Table(name = "case_classification")
@Getter
@Setter
@NoArgsConstructor
public class CaseClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** APPROVE | REJECT, normalized before it gets here. */
    @Column(nullable = false, length = 20)
    private String decision;

    @Column(name = "analyst_justification", columnDefinition = "text")
    private String analystJustification;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "classification_attempts", nullable = false)
    private int classificationAttempts;

    /** Null when the case was resolved by the deterministic Fast Track, with no model run. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "llm_analysis_id")
    private LlmAnalysis llmAnalysis;

    /**
     * {@code claims_analyst.id} of whoever decided. A plain column rather than an association:
     * the analyst profile is written by auth-service, and importing its entity would couple the
     * two modules — the database still enforces the FK, both tables being in the same tenant
     * schema.
     */
    @Column(name = "analyst_id", nullable = false)
    private Long analystId;
}
