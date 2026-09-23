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
 * A name/value datum no rule reads (invoice number, serial, store). For display, never comparison:
 * {@link #name} is whatever the model called it, so looking it up would break silently. Data a rule
 * compares belongs in {@link DocumentAnalysis} as a typed column.
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

    @Column(nullable = false, length = 100)
    private String name;

    /** Verbatim: normalizing would lose what the document actually said. */
    @Column(nullable = false, length = 500)
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private DocumentAnalysis analysis;
}
