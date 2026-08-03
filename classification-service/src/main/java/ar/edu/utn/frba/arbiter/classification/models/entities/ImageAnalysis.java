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
 * One analysed image: its CLIP embedding, the closest internal match, and any external web
 * hit ("analisis_imagen" in the DER). Replaces the old split between {@code image_embedding}
 * and {@code image_analysis} — the DER models a single row per image, and keeping the vector
 * in a separate table only forced a join that carried no extra information.
 *
 * <p>{@code caseDocumentId} points at the {@code case_documents} row the image came from,
 * forwarded by cases-service when it fires the classification. It is a plain column rather
 * than a JPA association: {@code CaseDocument} belongs to cases-service, and importing another
 * module's entity would couple the two — same criterion as {@code classification_log.case_id}.
 * The database still enforces the FK, since both tables live in the same tenant schema.
 *
 * <p>{@code embedding} is a pgvector column with no JPA type behind it. Hibernate never reads
 * or writes it: {@link ar.edu.utn.frba.arbiter.classification.models.repositories.ImageAnalysisRepository}
 * handles it with native SQL, which is also where the cosine search lives.
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

    /** {@code case_documents.id} this image came from. */
    @Column(name = "case_document_id", nullable = false)
    private Long caseDocumentId;

    /**
     * Model that produced {@link #embedding}. Vectors from different models are not
     * comparable, so a stored one is only meaningful next to the model that made it.
     */
    @Column(nullable = false, length = 80)
    private String model;

    /** The closest matching document found, when the similarity cleared the threshold. */
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
