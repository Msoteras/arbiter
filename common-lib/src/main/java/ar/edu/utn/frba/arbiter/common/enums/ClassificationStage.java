package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What the classification of a case is doing right now, in the order the orchestrator runs it.
 * Reported step by step by classification-service and pushed to the engine board; progress only,
 * never part of the audit trail. A run can end early, so a later stage may never show up.
 */
public enum ClassificationStage {
    /** Accepted and waiting for a free classification thread. */
    QUEUED,
    POLICY,
    HISTORY,
    RULES,
    /** Prescription and coverage exclusion, which settle the case before any document is read. */
    EXCLUSION,
    /** One attachment read by the vision model; the step carries its type and position. */
    DOCUMENT,
    RULE_EVALUATION,
    FAST_TRACK,
    DOCUMENT_SCHEDULE,
    MODEL,
    IMAGES,
    RISK,
    FINISHED,
    FAILED;

    public boolean terminal() {
        return this == FINISHED || this == FAILED;
    }
}
