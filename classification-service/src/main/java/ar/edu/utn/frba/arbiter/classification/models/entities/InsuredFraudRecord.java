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
 * A fraud determination that outlives the case it came from. Until this table existed Arbiter
 * remembered the file but not the person: an insured whose fraud had been verified started their
 * next claim as if nothing had happened.
 *
 * <p>It lives in classification-service rather than in cases-service, where the analyst confirms
 * it, for two reasons. This is the module that already holds cross-claim memory about an insured
 * (the pgvector image embeddings that catch a photo reused between claims), and it's the module
 * that reads the record on the hot path — the risk evaluators run inside the classification, and a
 * REST hop back to cases-service for every scored claim would buy nothing.
 *
 * <p>Nothing here is ever rewritten. The record is a statement about a person, so what it says and
 * who said it have to still be readable years later (Disposición SSN 2/2023, and Ley 25.326, which
 * makes marking someone a defrauder a thing you must be able to justify). It stops counting by
 * ageing out of the insurer's configured window, never by being edited.
 *
 * <p>{@code caseId} and {@code declaredByAnalystId} are logical references to cases-service's
 * tables — same criterion as {@link LlmAnalysis#getCaseId()}. The DB still has the FKs (same tenant
 * schema); what stays out is the JPA association, so one entity isn't owned by two modules.
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

    /**
     * The insured's DNI, which is how the person travels across module boundaries
     * ({@code ClaimReport.insuredId}) and is unique within the tenant. Kept as the key rather than
     * {@code insured.id} because it's what the engine already has when the next claim arrives.
     */
    @Column(name = "insured_dni", nullable = false, length = 20)
    private String insuredDni;

    /** The case the determination came out of — one record per case at most. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudRecordSource source;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** The expert assessment backing an {@code EXPERT_BACKED} record; null otherwise. */
    @Column(name = "expert_assessment_id")
    private Long expertAssessmentId;

    @Column(name = "declared_by_analyst_id", nullable = false)
    private Long declaredByAnalystId;

    /** Copied, not joined: the record has to stay readable after the analyst leaves the company. */
    @Column(name = "declared_by_analyst_name", nullable = false, length = 255)
    private String declaredByAnalystName;

    @CreationTimestamp
    @Column(name = "declared_at", nullable = false, updatable = false)
    private Instant declaredAt;

    /**
     * Whether the record still counts, against the insurer's window. Computed and not stored: a
     * stored flag would need a job to flip it, and the day the referente changes the window every
     * stored value is wrong at once.
     */
    public boolean inForce(int windowMonths, LocalDate today) {
        LocalDate declaredOn = declaredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        return !declaredOn.isBefore(today.minusMonths(windowMonths));
    }

    /** In force <b>and</b> backed by an expert: the only combination the engine acts on. */
    public boolean counts(int windowMonths, LocalDate today) {
        return source == FraudRecordSource.EXPERT_BACKED && inForce(windowMonths, today);
    }
}
