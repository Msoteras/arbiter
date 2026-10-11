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
 * One datum the document states about the item itself (make, model, IMEI, a plate), under the name
 * the policy uses for it. Unlike {@link DocumentDetail} these are compared: the extraction is asked
 * for the policy's own names, so {@link #name} can be looked up.
 */
@Entity
@Table(name = "document_attribute")
@Getter
@Setter
@NoArgsConstructor
public class DocumentAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    /** TEXT and never truncated: a cut value would be wrong, not incomplete, raising false findings. */
    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private DocumentAnalysis analysis;
}
