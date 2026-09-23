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

/** The analyst's verdict on a case; no case reaches a final state without one. */
@Entity
@Table(name = "case_classification")
@Getter
@Setter
@NoArgsConstructor
public class CaseClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** APPROVE | REJECT. */
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

    /** {@code claims_analyst.id}; a plain column because that entity belongs to another module (the DB keeps the FK). */
    @Column(name = "analyst_id", nullable = false)
    private Long analystId;
}
