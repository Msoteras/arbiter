package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /** Previous status; null when this row records the case's creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    private CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 40)
    private CaseStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusChangeActor actor;

    /** Short human-readable trigger, e.g. "clasificación: FALTA_DOCUMENTACION". */
    @Column(nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;
}
