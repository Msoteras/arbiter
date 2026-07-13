package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable, auditable record of every analysis run (SSN Disposición 2/2023):
 * one row per classification, with the model's result, the factors backing it,
 * the source (Fast Track rules or LLM), and — once the analyst decides — their verdict.
 *
 * This module does NOT persist the claim/case itself (that's cases-service's job): the
 * log only references the owning case by id. It's a historical record, not a navigable
 * relationship.
 */
@Entity
@Table(name = "classification_log")
@Getter
@Setter
@NoArgsConstructor
public class ClassificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Id of the case (owned by cases-service) this classification belongs to.
     * Null when the classification runs isolated, with no case behind it (test endpoint).
     */
    @Column(name = "case_id")
    private Long caseId;

    /** Model that produced the classification (e.g. "qwen3-vl"). Null when it was a rules-based Fast Track. */
    @Column(length = 80)
    private String model;

    /** Prompt version used. Null when it was a rules-based Fast Track. */
    @Column(name = "prompt_version", length = 80)
    private String promptVersion;

    /** RULES_FAST_TRACK | LLM */
    @Column(nullable = false, length = 40)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Classification classification;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Convert(converter = StringListJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<String> factors;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** Filled in once the analyst makes their decision (decision endpoint, next step). */
    @Column(name = "analyst_id", length = 80)
    private String analystId;

    @Column(length = 20)
    private String decision;

    @Column(name = "decision_timestamp")
    private Instant decisionTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
