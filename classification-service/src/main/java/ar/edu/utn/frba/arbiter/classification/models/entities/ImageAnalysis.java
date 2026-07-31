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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result of comparing one embedded image against internal duplicates and external web
 * matches ("analisis_imagen" in the DER, Bucket B — decided to normalize instead of
 * keeping it as the {@code forensic_report} JSON blob it lives in today).
 *
 * <p>Adaptation from the DER: it draws its own {@code embedding} column, duplicating
 * what {@link ImageEmbedding} already stores. Since both entities are in this same
 * module, this references {@link ImageEmbedding} by a real FK instead of repeating the
 * vector — same image, same embedding, no reason to store it twice.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_embedding_id", nullable = false)
    private ImageEmbedding imageEmbedding;

    @Column(name = "similarity_score", precision = 5, scale = 4)
    private BigDecimal similarityScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "similar_image_embedding_id")
    private ImageEmbedding similarImageEmbedding;

    @Column(nullable = false)
    private boolean suspicious;

    @Column(nullable = false)
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
