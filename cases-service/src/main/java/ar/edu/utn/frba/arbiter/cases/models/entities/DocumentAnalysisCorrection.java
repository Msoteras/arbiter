package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "document_analysis_correction")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentAnalysisCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_document_id", nullable = false)
    private Long caseDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExtractedField field;

    @Column(name = "field_name", length = 100)
    private String fieldName;

    @Column(name = "previous_value", columnDefinition = "text")
    private String previousValue;

    @Column(name = "new_value", nullable = false, columnDefinition = "text")
    private String newValue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analyst_id", nullable = false)
    private ClaimsAnalyst analyst;

    @CreationTimestamp
    @Column(name = "corrected_at", nullable = false, updatable = false)
    private Instant correctedAt;
}
