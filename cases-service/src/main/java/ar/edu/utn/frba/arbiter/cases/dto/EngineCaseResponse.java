package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;

import java.time.Instant;

/**
 * A case as the engine board shows it: its latest classification run, reduced to the step it is
 * on. The same shape goes in the snapshot and in every push, so the board replaces a case by id.
 *
 * @param stage     the last step reported; {@code QUEUED} while waiting, terminal once done
 * @param stageAt   when that step started
 * @param startedAt when a thread took it; null while queued
 * @param result    only once {@code FINISHED}
 */
public record EngineCaseResponse(
        Long caseId,
        String insured,
        String claimCause,
        ClassificationStage stage,
        String detail,
        Integer itemIndex,
        Integer itemCount,
        Classification result,
        Instant queuedAt,
        Instant startedAt,
        Instant stageAt) {
}
