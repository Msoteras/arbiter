package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A fraud determination that outlives its case. Lives here because the risk evaluators read it on
 * the classification hot path. Never rewritten (Ley 25.326 requires it stay justifiable): it stops
 * counting by ageing out of the insurer's window. {@code caseId} and {@code declaredByAnalystId} are
 * plain columns referencing cases-service's tables.
 */
@Entity
@Table(name = "insured_fraud_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsuredFraudRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DNI rather than {@code insured.id}: it's what the engine has when the next claim arrives. */
    @Column(name = "insured_dni", nullable = false, length = 20)
    private String insuredDni;

    /** At most one record per case. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudRecordSource source;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** Only for {@code EXPERT_BACKED} records. */
    @Column(name = "expert_assessment_id")
    private Long expertAssessmentId;

    @Column(name = "declared_by_analyst_id", nullable = false)
    private Long declaredByAnalystId;

    /** Copied, not joined: it must stay readable after the analyst leaves the company. */
    @Column(name = "declared_by_analyst_name", nullable = false, length = 255)
    private String declaredByAnalystName;

    @CreationTimestamp
    @Column(name = "declared_at", nullable = false, updatable = false)
    private Instant declaredAt;

    /** Computed, not stored: the referente can change the window at any time. */
    public boolean inForce(int windowMonths, LocalDate today) {
        LocalDate declaredOn = declaredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        return !declaredOn.isBefore(today.minusMonths(windowMonths));
    }

    /** In force and expert-backed: the only combination the engine acts on. */
    public boolean counts(int windowMonths, LocalDate today) {
        return source == FraudRecordSource.EXPERT_BACKED && inForce(windowMonths, today);
    }
}
