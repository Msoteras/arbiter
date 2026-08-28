package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Append-only record of every case status transition: one row per move, with where it came from,
 * where it went, who drove it and why. Written only through {@code CaseStatusService} so no
 * transition escapes the trail. No setters — immutable once created.
 *
 * <p>The two states are FKs to the platform catalog ({@code arbiter_common.case_status}); the
 * transitions themselves are tenant data, which is why this table lives in the tenant schema.
 */
@Entity
@Table(name = "case_status_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** Where the case came from; null when this row records the case's creation. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "initial_status_id")
    private CaseState initialStatus;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "final_status_id", nullable = false)
    private CaseState finalStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusChangeActor actor;

    /** Short human-readable trigger, e.g. "clasificación: FALTA_DOCUMENTACION". */
    @Column(nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    /** Null on the creation row — see {@link #initialStatus}. */
    @Transient
    public CaseStatus getFromStatus() {
        return initialStatus == null ? null : CaseStatus.valueOf(initialStatus.getName());
    }

    @Transient
    public CaseStatus getToStatus() {
        return CaseStatus.valueOf(finalStatus.getName());
    }
}
