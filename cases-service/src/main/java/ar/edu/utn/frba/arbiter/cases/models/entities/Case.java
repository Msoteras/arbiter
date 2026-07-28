package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "cases", indexes = {
        @Index(name = "idx_cases_risk_band", columnList = "risk_band")
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

    @Column(nullable = false)
    private String branch;

    @Column(nullable = false)
    private String product;

    @Column(nullable = false)
    private String claimCause;

    @Column(nullable = false)
    private String insuredItem;

    @Column(nullable = false)
    private String insuredId;

    /**
     * Insured's real name, resolved from the policy by classification-service and cached here
     * read-only from the classification poll (ClaimResponse) — same pattern as riskScore/riskBand.
     * Null until the first classification resolves ("sin scorear"-style: not the same as "no
     * tiene nombre", just "todavía no lo sabemos").
     */
    private String insuredName;

    @Column(nullable = false)
    private String policyNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime eventDate;

    @Column(nullable = false)
    private String eventLocation;

    private BigDecimal claimedAmount;

    @Column(nullable = false)
    @Builder.Default
    private boolean pep = false;

    /**
     * Insured's consent to have their claim images analyzed for fraud indicators (reuse
     * detection, web search), captured at denuncia time (H0009). Declarative, same pattern
     * as {@code pep} — not surfaced back on CaseResponse, just persisted for the audit trail.
     */
    @Column(name = "image_consent", nullable = false)
    @Builder.Default
    private boolean imageConsent = false;

    private String contactEmail;

    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Classification analysisClassification;

    private Double analysisConfidence;

    @Column(columnDefinition = "TEXT")
    private String analysisDetail;

    private Boolean deterministicFastTrack;

    /**
     * Cached parallel fraud/risk score, populated read-only from the classification poll
     * (ClaimResponse). The authoritative snapshot lives in classification-service's
     * classification_log; the case only caches it for the analyst's fraud-gauge and for filtering.
     * All three are null when the claim wasn't scored ("sin scorear") — never surfaced as a real LOW.
     * {@code riskBand} is indexed so H0011 can filter cases by fraud-alert level.
     */
    private Double riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", length = 20)
    private RiskBand riskBand;

    @Convert(converter = RiskBreakdownJsonConverter.class)
    @Column(name = "risk_breakdown", columnDefinition = "TEXT")
    private List<RiskBreakdownItem> riskBreakdown;

    /**
     * Structured image-fraud analysis, populated read-only from the classification poll
     * (ClaimResponse) — same pattern as riskScore/riskBand/riskBreakdown. Cached here so the
     * analyst UI's forensic tab can read it from GET /cases/{id} without calling
     * classification-service directly. Null when no analysis ran (Fast Track, isolated test, or
     * a case with no image attachments).
     */
    @Convert(converter = ImageForensicReportJsonConverter.class)
    @Column(name = "forensic_report", columnDefinition = "TEXT")
    private ImageForensicReport forensicReport;

    /**
     * Free-text note where the analyst records an adjustment to the score WITHOUT overwriting the
     * computed value (the score is a suggestion). Nullable; the write endpoint belongs to the
     * analyst portal work — here it's only the field and its persistence.
     */
    @Column(name = "manual_adjustment_note", columnDefinition = "TEXT")
    private String manualAdjustmentNote;

    @Builder.Default
    @Column(nullable = false)
    private int classificationAttempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
