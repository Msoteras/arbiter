package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One step of a classification as classification-service reported it. Write-once. Not named
 * {@code ClassificationStep} to keep it apart from the DTO of the same shape in common-lib.
 */
@Entity
@Table(name = "classification_step")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassificationStepRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClassificationStage stage;

    @Column(length = 50)
    private String detail;

    @Column(name = "item_index")
    private Integer itemIndex;

    @Column(name = "item_count")
    private Integer itemCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private Classification result;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
