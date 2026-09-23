package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Why an async classification gave up after its retries. Written by classification-service onto
 * {@code cases.classification_failure_reason}; cases-service only auto-requeues
 * {@link #INFRASTRUCTURE} failures, since {@link #OTHER} (bugs, bad input) would just fail again.
 */
public enum ClassificationFailureReason {
    INFRASTRUCTURE,
    OTHER
}
