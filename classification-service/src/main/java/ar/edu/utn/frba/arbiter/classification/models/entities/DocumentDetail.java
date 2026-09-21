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
 * One datum the document states that no rule reads — the invoice number, the serial, the store
 * ("dato_documento" in the DER). Name and value instead of a column each, so extending what the
 * model extracts doesn't cost a migration every time.
 *
 * <p>Sibling of {@link DocumentVisualFinding} in shape, opposite in meaning: that one is an
 * observation about how the image <b>looks</b>, this one is content the document <b>says</b>. Both
 * hang off {@link DocumentAnalysis} and are replaced with it on every run.
 *
 * <p><b>For display, never for comparison.</b> {@link #name} is whatever the model called the
 * datum, so it is not a contract: code that looked up a name here would break as soon as the model
 * worded it differently, and would break silently — the check would stop running, which this
 * engine reads as "nothing wrong". Data a rule compares belongs in {@link DocumentAnalysis} as a
 * typed column, which is also where a detail goes once a rule starts needing it.
 */
@Entity
@Table(name = "document_detail")
@Getter
@Setter
@NoArgsConstructor
public class DocumentDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the document calls it, as read ("N° de factura"). Not an identifier. */
    @Column(nullable = false, length = 100)
    private String name;

    /** The value verbatim, unnormalized: normalizing here would lose what the paper actually said. */
    @Column(nullable = false, length = 500)
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private DocumentAnalysis analysis;
}
