package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * A stage a classification just entered, reported by classification-service to cases-service.
 * {@code occurredAt} is taken where the step happened, so a late delivery keeps the real timing.
 *
 * @param detail the document type code for {@link ClassificationStage#DOCUMENT}; null otherwise
 * @param itemIndex 1-based position of the document among the ones read in that pass
 * @param itemCount documents read in that pass
 * @param result the outcome, only on {@link ClassificationStage#FINISHED}
 */
public record ClassificationStep(
        @NotNull ClassificationStage stage,
        String detail,
        Integer itemIndex,
        Integer itemCount,
        Classification result,
        @NotNull Instant occurredAt) {

    public static ClassificationStep of(ClassificationStage stage) {
        return new ClassificationStep(stage, null, null, null, null, Instant.now());
    }

    public static ClassificationStep document(String type, int index, int count) {
        return new ClassificationStep(ClassificationStage.DOCUMENT, type, index, count, null, Instant.now());
    }

    public static ClassificationStep finished(Classification result) {
        return new ClassificationStep(ClassificationStage.FINISHED, null, null, null, result, Instant.now());
    }
}
