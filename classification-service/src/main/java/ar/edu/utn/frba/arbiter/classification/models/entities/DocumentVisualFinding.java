package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One observable sign that a document may have been altered or fabricated
 * ("hallazgo_visual_documento" in the DER) — mismatched typefaces, pasted text, a warped stamp.
 *
 * <p>It is an observation about the <b>image</b>, not content of the document: that separation is
 * the whole point of keeping it out of {@link DocumentAnalysis#getTranscription()}, so nothing
 * downstream can read it as something the paper itself said.
 *
 * <p>Never conclusive on its own — it informs the analyst's reading, it doesn't decide a rule.
 */
@Entity
@Table(name = "document_visual_finding")
@Getter
@Setter
@NoArgsConstructor
public class DocumentVisualFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String finding;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private DocumentAnalysis analysis;
}
