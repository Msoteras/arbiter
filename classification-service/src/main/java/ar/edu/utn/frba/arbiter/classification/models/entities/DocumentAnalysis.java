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
 * What the vision pass read out of one attachment ("analisis_documento" in the DER): the
 * transcription plus that same data already typed. Sibling of {@link ImageAnalysis} — that one is
 * about how the image <b>looks</b> (embedding, reuse across claims), this one about what the
 * document <b>says</b>.
 *
 * <p>It is the persisted form of {@link DocumentExtraction}, which until now lived only inside a
 * single classification run and was dropped when it ended. The data already fed the prompt and
 * {@code DocumentInconsistencyEvaluator}; what was missing was letting the analyst see it.
 *
 * <p><b>Every field is nullable and that is normal</b>: a photo of the broken phone carries no
 * amount, a police report no IMEI. Null means "the document doesn't say it", never "it doesn't
 * match" — a missing field must never be read as an inconsistency.
 *
 * <p>One row per document, replaced on each run (unlike {@link LlmAnalysis}, which is append-only).
 * What Disposición SSN 2/2023 audits is the recommendation and its reasons; this is the supporting
 * read the analyst looks at, and keeping every historical extraction of the same unchanged PDF
 * would pile up noise without adding a fact.
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

    /** The date on the document — the event's or the issue date, whichever it states. */
    @Column(name = "document_date")
    private LocalDate documentDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "item_description", length = 255)
    private String itemDescription;

    @Column(length = 20)
    private String imei;

    /** Never null: {@code DESCONOCIDO} is a real answer, not a missing one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "affected_party", nullable = false, length = 20)
    private DocumentExtraction.AffectedParty affectedParty;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    /**
     * The attachment this was read from. A plain column, like {@code caseId} elsewhere in this
     * module: {@code case_documents} belongs to cases-service and this is a historical reference,
     * not a navigable relationship.
     */
    @Column(name = "case_document_id", nullable = false)
    private Long caseDocumentId;

    /**
     * Signs of tampering noticed while looking at the image, one row each rather than a serialized
     * list — same criterion as {@link LlmReason}. <b>Empty is the normal case</b>, and an empty
     * list is not evidence that the document is authentic.
     */
    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentVisualFinding> visualFindings = new ArrayList<>();

    /** Keeps both sides of the association consistent when building the aggregate. */
    public void addVisualFinding(String finding) {
        DocumentVisualFinding row = new DocumentVisualFinding();
        row.setFinding(finding);
        row.setAnalysis(this);
        visualFindings.add(row);
    }
}
