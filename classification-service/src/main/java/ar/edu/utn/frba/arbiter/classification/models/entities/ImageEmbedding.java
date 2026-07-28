package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "image_embedding")
@Getter
@Setter
@NoArgsConstructor
public class ImageEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "attachment_label", length = 120)
    private String attachmentLabel;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "model", nullable = false, length = 80)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
