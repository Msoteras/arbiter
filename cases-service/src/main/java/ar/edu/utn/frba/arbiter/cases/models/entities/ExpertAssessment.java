package ar.edu.utn.frba.arbiter.cases.models.entities;

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

import java.time.Instant;

/**
 * One derivation of a case to an external expert, and the report that comes back ("peritaje").
 *
 * <p>The point of the round trip is evidence: rejecting a claim needs "una causa legal o
 * convencional de exclusión", and a model's fraud suspicion is not one. The expert turns the
 * suspicion into a verified fact — or discards it. It still resolves nothing: the case returns
 * to the analyst, who decides (decisión de arquitectura #5).
 *
 * <p>One row per case: the analyst derives once.
 */
@Entity
@Table(name = "expert_assessment",
        uniqueConstraints = @UniqueConstraint(columnNames = "case_id"))
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

    /**
     * Copied off the catalog rather than read through {@link #expertFirm}: the firm can be
     * renamed or deactivated later, and who verified <b>this</b> claim must not change with it
     * (same reasoning as {@code PolicySnapshot}).
     */
    @Column(name = "expert_name", nullable = false, length = 120)
    private String expertName;

    @Column(name = "expert_email", nullable = false, length = 150)
    private String expertEmail;

    /** Why the analyst derived it, in their words. Part of the trail, not free-form metadata. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "derived_at", nullable = false, updatable = false)
    private Instant derivedAt;

    /**
     * When the email to the expert actually went out. Null means it never did — the send is
     * best-effort like every other notification, and without this column a case could sit
     * waiting on someone nobody ever asked.
     */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /**
     * Null until the report is in. Not derivable from the document's {@code uploadedAt}: these
     * two dates are the round trip, and the round trip is what interrupts the art. 56 term.
     */
    @Column(name = "report_received_at")
    private Instant reportReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExpertVerdict verdict;

    @Column(name = "verdict_note", columnDefinition = "TEXT")
    private String verdictNote;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "derived_by", nullable = false)
    private ClaimsAnalyst derivedBy;

    /** Nullable on purpose: the assessment outlives the catalog row it came from. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "expert_firm_id")
    private ExpertFirm expertFirm;

    /** The uploaded report, as a row of {@code case_documents} like every other attachment. */
    @Column(name = "report_document_id")
    private Long reportDocumentId;

    public boolean isAwaitingReport() {
        return reportReceivedAt == null;
    }
}
