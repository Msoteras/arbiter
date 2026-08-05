package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
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

    /**
     * What the claim is about. The branch ("Ramo") is not a column: it hangs off the cause, which
     * is unique per {@code (branch_id, name)} — {@code claimCause.getBranch()} is the only place
     * it lives now.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "claim_cause_id", nullable = false)
    private ClaimCause claimCause;

    /**
     * The item as the insured described it, free text on purpose: it's a declaration, not a
     * catalog lookup, and comparing it against what the policy has on file is exactly what
     * surfaces a declared-vs-recorded gap.
     */
    @Column(name = "declared_item")
    private String declaredItem;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "insured_id", nullable = false)
    private Insured insured;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    /**
     * Denormalized from {@code policy.coverage_id} because the DER makes it a column of its own:
     * a policy's coverage can be re-pointed by a later sync, and the case has to keep the one it
     * was filed under.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    /** What the insurer's DB answered when this case was filed. Null until the sync logs it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_snapshot_id")
    private PolicySnapshot policySnapshot;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /**
     * When the insured filed the police report ("fecha_hora_denuncia_policial") — not every
     * claim requires one (e.g. "Rotura accidental"), so this is nullable and, unlike
     * {@code occurredAt}, the denuncia doesn't always carry it.
     */
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
     * When the insurer must have answered by: 30 days from the denuncia, per Ley 17.418 art. 56.
     * Set at creation — see {@code CaseServiceImpl.RESPONSE_TERM_DAYS}.
     */
    @Column(name = "response_deadline", nullable = false)
    private LocalDate responseDeadline;

    /**
     * The case's current state, as a row of the platform catalog rather than an enum column
     * (H0015: the referent configures the flow's states). Eager because every read of a case
     * needs it — the API, the inbox and the state machine all go through {@link #getStatus()}.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "current_status_id", nullable = false)
    private CaseState currentStatus;

    // La recomendación del modelo, su confianza y sus motivos NO son columnas de `cases`: la
    // bandeja no filtra por clasificación, solo la muestra, así que se joinean desde
    // `llm_analysis` (ver CaseAnalysisRepository). Se denormaliza únicamente lo que se filtra.

    /**
     * Whether the deterministic gate resolved this case with no model run. Maps to
     * {@code was_fast_track}, the same column classification-service writes through
     * {@code CaseOutcomeRepository} — a Fast Track leaves no {@code llm_analysis} row, so without
     * this "fue Fast Track" and "todavía no se clasificó" are indistinguishable.
     */
    // Arranca en false, no en null: la columna es NOT NULL DEFAULT false, y el default del
    // esquema no aplica porque Hibernate incluye la columna en el INSERT igual. Da lo mismo
    // para quien lee — wasFastTracked() ya trata null y false por igual.
    @Builder.Default
    @Column(name = "was_fast_track", nullable = false)
    private Boolean deterministicFastTrack = false;

    /**
     * Final human determination that the claim was fraudulent ("fue_determinado_fraude") —
     * distinct from {@code riskBand}, which is the model's suggestion, not a verdict. Nothing
     * writes this yet: there is no analyst-facing "mark as fraud" flow today, only the
     * approve/reject decision. Defaults to {@code false} to match the column's default.
     */
    @Builder.Default
    @Column(name = "fraud_determined", nullable = false)
    private boolean fraudDetermined = false;

    /**
     * Where the case goes after the decision ("destino") — e.g. pago, rechazo, derivación a
     * investigación. Mapped as a plain string, <b>not</b> a Java enum: unlike {@code CaseStatus}
     * or {@code Classification}, the DER never enumerates this column's values (in the .mdj it is
     * drawn as a bare {@code VARCHAR(40)}, not an actual enumeration type) — modeling one here
     * would mean inventing business values with no source. Nothing writes it yet.
     */
    @Column(length = 40)
    private String destination;

    /**
     * Which scoring configuration produced {@link #riskScore}. A raw id and not an association,
     * same pattern as {@link #classificationId}: {@code scoring_configuration} belongs to
     * rules-service, which owns the entity. Null today because classification-service doesn't
     * call rules-service yet (still {@code MockRulesAdapter} — see CLAUDE.md's roadmap); nothing
     * upstream has a real id to hand over.
     */
    @Column(name = "scoring_configuration_id")
    private Long scoringConfigurationId;

    /**
     * Cached parallel fraud/risk score, populated read-only from the classification poll
     * (ClaimResponse). {@code risk_analysis} stays the auditable source; these two are here
     * because the inbox <b>filters and sorts</b> by band ({@code riskBand} is indexed for H0011)
     * — the score breakdown, which is only displayed, is joined instead.
     * Both are null when the claim wasn't scored ("sin scorear") — never surfaced as a real LOW.
     */
    // La columna es numeric(4,3), igual que risk_analysis.risk_score (la fuente auditable).
    // Se mantiene Double del lado Java porque es lo que exponen ClaimResponse/CaseResponse y el
    // frontend ya consume; el tipo JDBC se declara acá para que coincida con el esquema.
    @Column(name = "risk_score", precision = 4, scale = 3)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Double riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", length = 20)
    private RiskBand riskBand;

    /**
     * Structured image-fraud analysis, populated read-only from the classification poll
     * (ClaimResponse) — same pattern as riskScore/riskBand. Cached here so the
     * analyst UI's forensic tab can read it from GET /cases/{id} without calling
     * classification-service directly. Null when no analysis ran (Fast Track, isolated test, or
     * a case with no image attachments).
     */
    // jsonb, no TEXT: así está la columna en el esquema, y con ddl-auto=validate un TEXT acá
    // haría fallar el arranque. El converter sigue produciendo el String; @JdbcTypeCode es lo
    // que le dice a Hibernate cómo escribirlo.
    @Convert(converter = ImageForensicReportJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forensic_report", columnDefinition = "jsonb")
    private ImageForensicReport forensicReport;

    /**
     * Free-text note where the analyst records an adjustment to the score WITHOUT overwriting the
     * computed value (the score is a suggestion). Nullable; the write endpoint belongs to the
     * analyst portal work — here it's only the field and its persistence.
     */
    @Column(name = "manual_adjustment_note", columnDefinition = "TEXT")
    private String manualAdjustmentNote;

    /**
     * Analyst who owns this case ("analista asignado"). Nullable: an unassigned case is the normal
     * starting state, not an error. One analyst at a time — reassigning overwrites, it doesn't
     * stack (see {@code CaseService.assignAnalyst}).
     *
     * <p>A real association and not a cached id + name: {@code claims_analyst} lives in this same
     * tenant schema, so the display name is one join away and the FK keeps the pair consistent.
     * That also bounds assignment to the insurer's own analysts for free — an id from another
     * tenant simply isn't in this schema.
     *
     * <p>EAGER like its siblings on this entity: the inbox renders the analyst's name on every
     * row, and with open-in-view off (see application.yml) a LAZY proxy would be resolved after
     * the session closed.
     *
     * <p>Assignment is ownership, not resolution: the case still needs the analyst's explicit
     * approve/reject to move forward (decisión de arquitectura #5, human-in-the-loop).
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "analyst_id")
    private ClaimsAnalyst analyst;

    @Builder.Default
    @Column(name = "classification_attempts", nullable = false)
    private int classificationAttempts = 0;

    /**
     * The {@code case_classification} row holding the analyst's verdict — and through its
     * {@code llm_analysis_id}, which model run backed it. Without this the case knows *what* was
     * decided but not *which* classification it was deciding on, which is the link the audit trail
     * (Disposición SSN 2/2023) hangs off.
     *
     * <p>A raw id and not an association: the table belongs to classification-service, which owns
     * the entity. Null until the analyst decides — the row doesn't exist before that. The FK is
     * added by {@code ALTER TABLE} in the schema script because it closes a cycle
     * (cases → case_classification → llm_analysis → cases).
     */
    @Column(name = "classification_id")
    private Long classificationId;

    /** When the denuncia was filed ("fecha_hora_denuncia_siniestro"), as opposed to when it happened. */
    @CreationTimestamp
    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The current state as the enum the state machine and the API speak. Read-only on purpose:
     * writing a status means resolving a catalog row and leaving an audit trail, which is
     * {@code CaseStatusService}'s job — see {@code CaseStateCatalog} for the translation.
     */
    @Transient
    public CaseStatus getStatus() {
        return currentStatus == null ? null : CaseStatus.valueOf(currentStatus.getName());
    }
}
