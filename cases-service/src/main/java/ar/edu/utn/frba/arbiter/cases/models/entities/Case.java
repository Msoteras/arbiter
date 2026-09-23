package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cases", indexes = {
        @Index(name = "idx_cases_risk_band", columnList = "risk_band"),
        @Index(name = "idx_cases_analyst", columnList = "analyst_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Case {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The branch is not a column: it's reached through {@code claimCause.getBranch()}. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "claim_cause_id", nullable = false)
    private ClaimCause claimCause;

    /** Free text on purpose: comparing the declaration against the policy surfaces discrepancies. */
    @Column(name = "declared_item")
    private String declaredItem;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "insured_id", nullable = false)
    private Insured insured;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    /** The coverage the case was filed under; a policy holds several. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    /** Null until classification-service records it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_snapshot_id")
    private PolicySnapshot policySnapshot;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** Nullable: not every claim cause requires a police report. */
    @Column(name = "police_report_at")
    private LocalDateTime policeReportAt;

    @Column(name = "event_address", nullable = false)
    private String eventAddress;

    private String locality;

    private String province;

    @Column(name = "event_latitude")
    private BigDecimal eventLatitude;

    @Column(name = "event_longitude")
    private BigDecimal eventLongitude;

    @Column(name = "claimed_amount")
    private BigDecimal claimedAmount;

    /**
     * 30 days from the claim report (art. 56, Ley 17.418). Reset to a fresh 30 days, not resumed,
     * every time the case leaves a status that interrupts the term.
     */
    @Column(name = "response_deadline", nullable = false)
    private LocalDate responseDeadline;

    /** A catalog row rather than an enum column. Eager because every read goes through {@link #getStatus()}. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "current_status_id", nullable = false)
    private CaseState currentStatus;

    /**
     * Written by classification-service. A Fast Track leaves no {@code llm_analysis} row, so this is
     * what tells it apart from "not classified yet". Initialized because Hibernate always includes
     * the column in the INSERT, bypassing the schema default.
     */
    @Builder.Default
    @Column(name = "was_fast_track", nullable = false)
    private Boolean deterministicFastTrack = false;

    /**
     * The human verdict, unlike {@code riskBand}. Written only after the insured's fraud record
     * exists, so a flagged case can always be traced back to it.
     */
    @Builder.Default
    @Column(name = "fraud_determined", nullable = false)
    private boolean fraudDetermined = false;

    /** Plain string, not an enum: its values were never defined. Nothing writes it yet. */
    @Column(length = 40)
    private String destination;

    /**
     * Raw id: the table belongs to rules-service. Written by classification-service with the score.
     * Null when the baseline scoring was used.
     */
    @Column(name = "scoring_configuration_id")
    private Long scoringConfigurationId;

    /**
     * Cached from {@code risk_analysis}, the auditable source, because the inbox filters and sorts by
     * band. Null when the claim wasn't scored, never a real LOW.
     */
    @Column(name = "risk_score", precision = 4, scale = 3)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Double riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", length = 20)
    private RiskBand riskBand;

    /** Null when no analysis ran (Fast Track, or no image attachments). */
    // jsonb in the schema: with ddl-auto=validate a TEXT mapping would fail at startup.
    @Convert(converter = ImageForensicReportJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forensic_report", columnDefinition = "jsonb")
    private ImageForensicReport forensicReport;

    /** The analyst's adjustment note, kept apart so the computed score is never overwritten. */
    @Column(name = "manual_adjustment_note", columnDefinition = "TEXT")
    private String manualAdjustmentNote;

    /**
     * Null means unassigned. A real FK within the tenant schema, which also bounds assignment to the
     * insurer's own analysts. EAGER because the inbox shows the name on every row and open-in-view is off.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "analyst_id")
    private ClaimsAnalyst analyst;

    @Builder.Default
    @Column(name = "classification_attempts", nullable = false)
    private int classificationAttempts = 0;

    /**
     * Written by classification-service; null while pending or after a success. Only
     * {@code INFRASTRUCTURE} failures are auto-requeued: anything else would fail the same way again.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification_failure_reason", length = 20)
    private ClassificationFailureReason classificationFailureReason;

    @Column(name = "classification_failure_message", columnDefinition = "TEXT")
    private String classificationFailureMessage;

    /**
     * Set when the claim was accepted while rules-service was down, so its documents are rechecked
     * later. A timestamp rather than a flag, to tell how long it waited.
     */
    @Column(name = "documents_unverified_since")
    private Instant documentsUnverifiedSince;

    /**
     * Links the verdict to the classification it decided on (audit trail). Raw id: the table belongs
     * to classification-service. Null until the analyst decides.
     */
    @Column(name = "classification_id")
    private Long classificationId;

    /** When the claim was reported, as opposed to when it happened. */
    @CreationTimestamp
    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Read-only on purpose: status changes go through {@code CaseStatusService}, which leaves the audit trail. */
    @Transient
    public CaseStatus getStatus() {
        return currentStatus == null ? null : CaseStatus.valueOf(currentStatus.getName());
    }
}
