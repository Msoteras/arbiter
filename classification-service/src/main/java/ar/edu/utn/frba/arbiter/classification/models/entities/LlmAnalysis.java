package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The model's recommendation for a case; with its {@link LlmReason} rows, the immutable audit trail
 * (Disposición SSN 2/2023). A Fast Track writes no row here: that is {@code cases.was_fast_track}.
 */
@Entity
@Table(name = "llm_analysis")
@Getter
@Setter
@NoArgsConstructor
public class LlmAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Never {@code FAST_TRACK}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Classification recommendation;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Column(precision = 5, scale = 3)
    private BigDecimal confidence;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** Null for analyses predating this check; absent is not {@code MATCHES}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cause_consistency", length = 20)
    private CauseConsistency causeConsistency;

    /** By name, not FK: immutable audit evidence must survive a later rename. */
    @Column(name = "suggested_claim_cause", length = 120)
    private String suggestedClaimCause;

    @Column(name = "cause_evidence", columnDefinition = "TEXT")
    private String causeEvidence;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LlmReason> reasons = new ArrayList<>();

    public void addReason(String reason) {
        LlmReason row = new LlmReason();
        row.setReason(reason);
        row.setAnalysis(this);
        reasons.add(row);
    }
}
