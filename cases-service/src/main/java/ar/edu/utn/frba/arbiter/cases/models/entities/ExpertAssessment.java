package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A derivation to an external provider and the report that comes back. It resolves nothing: the case
 * returns to the analyst. One row per case and provider type, since a case may go to both in turn.
 */
@Entity
@Table(name = "expert_assessment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"case_id", "provider_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpertAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** Copied off the catalog: renaming the firm later must not change who verified this claim. */
    @Column(name = "expert_name", nullable = false, length = 120)
    private String expertName;

    @Column(name = "expert_email", nullable = false, length = 150)
    private String expertEmail;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "derived_at", nullable = false, updatable = false)
    private Instant derivedAt;

    /** Null if the best-effort email never went out. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /** Not derivable from the document's {@code uploadedAt}: this round trip interrupts the art. 56 term. */
    @Column(name = "report_received_at")
    private Instant reportReceivedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    private ProviderType providerType = ProviderType.ESTUDIO_LIQUIDADOR;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExpertVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "repair_outcome", length = 20)
    private RepairOutcome repairOutcome;

    @Column(name = "verdict_note", columnDefinition = "TEXT")
    private String verdictNote;

    /** Null when the report gives no figure; a zero would mean "nothing is owed", a different conclusion. */
    @Column(name = "indemnifiable_amount")
    private BigDecimal indemnifiableAmount;

    /**
     * Quoted or invoiced repair price, the base of the repair formula; not {@code indemnifiableAmount},
     * which is an opinion on what the claim is worth. Required with {@code QUOTE_SENT}, optional with
     * {@code REPAIRED}, null with {@code IRREPARABLE}.
     */
    @Column(name = "repair_cost")
    private BigDecimal repairCost;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "derived_by", nullable = false)
    private ClaimsAnalyst derivedBy;

    /** Nullable on purpose: the assessment outlives the catalog row it came from. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "expert_firm_id")
    private ExpertFirm expertFirm;

    @Column(name = "report_document_id")
    private Long reportDocumentId;

    public boolean isAwaitingReport() {
        return reportReceivedAt == null;
    }
}
