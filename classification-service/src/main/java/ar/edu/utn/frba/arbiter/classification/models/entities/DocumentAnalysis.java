package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The persisted {@link DocumentExtraction} of one attachment. Null fields mean "the document doesn't
 * say", never "doesn't match". One row per document, replaced on each run (unlike the append-only
 * {@link LlmAnalysis}): it supports the analyst's reading, it isn't the audited recommendation.
 */
@Entity
@Table(name = "document_analysis")
@Getter
@Setter
@NoArgsConstructor
public class DocumentAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String transcription;

    @Column(name = "document_date")
    private LocalDate documentDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    // TEXT and never truncated: brand and model are compared against the insured item, and a cut
    // value would be wrong, not incomplete, raising false findings.
    @Column(name = "item_description", columnDefinition = "text")
    private String itemDescription;

    @Column(columnDefinition = "text")
    private String brand;

    @Column(columnDefinition = "text")
    private String model;

    @Column(length = 20)
    private String imei;

    /** Never null: {@code DESCONOCIDO} is a real answer. */
    @Enumerated(EnumType.STRING)
    @Column(name = "affected_party", nullable = false, length = 20)
    private DocumentExtraction.AffectedParty affectedParty;

    /**
     * The claim cause the document narrates, by name from the branch's catalog; null when it narrates
     * none. A name and not an FK to {@code claim_cause}, same as {@code llm_analysis.suggested_claim_cause}:
     * this row records what the extraction read, and has to keep saying so if the referente later
     * renames or removes the cause.
     */
    @Column(name = "described_claim_cause", length = 120)
    private String describedClaimCause;

    /** Anything but COMPLETE means the empty fields are a failed read, not what the document says. */
    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 20)
    private DocumentExtraction.Status extractionStatus;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    /** Plain column: {@code case_documents} belongs to cases-service. */
    @Column(name = "case_document_id", nullable = false)
    private Long caseDocumentId;

    /** Empty is the normal case, and not evidence that the document is authentic. */
    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentVisualFinding> visualFindings = new ArrayList<>();

    /** Displayed, never compared (see {@link DocumentDetail}). */
    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentDetail> details = new ArrayList<>();

    public void addVisualFinding(String finding) {
        DocumentVisualFinding row = new DocumentVisualFinding();
        row.setFinding(finding);
        row.setAnalysis(this);
        visualFindings.add(row);
    }

    public void addDetail(String name, String value) {
        DocumentDetail row = new DocumentDetail();
        row.setName(name);
        row.setValue(value);
        row.setAnalysis(this);
        details.add(row);
    }
}
