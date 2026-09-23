package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One analysed image: the closest internal match and any external web hit. The pgvector
 * {@code embedding} column has no JPA mapping; {@link ar.edu.utn.frba.arbiter.classification.models.repositories.ImageAnalysisRepository}
 * handles it with native SQL. {@code caseDocumentId} is a plain column because {@code case_documents}
 * belongs to cases-service.
 */
@Entity
@Table(name = "image_analysis")
@Getter
@Setter
@NoArgsConstructor
public class ImageAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_document_id", nullable = false)
    private Long caseDocumentId;

    /** Embeddings from different models are not comparable. */
    @Column(nullable = false, length = 80)
    private String model;

    @Column(name = "similar_document_id")
    private Long similarDocumentId;

    @Column(name = "similarity_score", precision = 5, scale = 2)
    private BigDecimal similarityScore;

    @Column(name = "is_suspicious", nullable = false)
    private boolean suspicious;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "external_source")
    private String externalSource;

    @Column(name = "external_reference", columnDefinition = "TEXT")
    private String externalReference;

    @Column(name = "match_type")
    private String matchType;

    @Column(name = "external_hash")
    private String externalHash;
}
