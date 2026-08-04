package ar.edu.utn.frba.arbiter.classification.models.entities;

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
 * What the model recommended for one case ("analisis_llm" in the DER). Immutable once written:
 * together with its {@link LlmReason} rows it is the audit trail Disposición SSN 2/2023 requires
 * — the recommendation, the reasons behind it, and when it was produced.
 *
 * <p>Only exists for classifications that actually went through the LLM. A deterministic Fast
 * Track produces no row here at all, which is why the table's CHECK rejects {@code FAST_TRACK}
 * as a recommendation (decision #6: the model can never return it). Whether a case was fast
 * tracked is {@code cases.was_fast_track}, not something inferred from here.
 *
 * <p>{@code caseId} is a plain column: the case belongs to cases-service, and this module keeps
 * a historical reference, not a navigable relationship.
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

    /** Never {@code FAST_TRACK} — see the class comment. */
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

    /** Id of the case (owned by cases-service) this analysis belongs to. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /**
     * The factors backing the recommendation, one row each instead of a serialized list —
     * that's what makes them queryable, which is the point of the audit requirement.
     */
    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LlmReason> reasons = new ArrayList<>();

    /** Keeps both sides of the association consistent when building the aggregate. */
    public void addReason(String reason) {
        LlmReason row = new LlmReason();
        row.setReason(reason);
        row.setAnalysis(this);
        reasons.add(row);
    }
}
