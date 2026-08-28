package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Why an async classification run ({@code ClaimClassificationService.processClaimClassification})
 * gave up after exhausting its retries. Written by classification-service directly onto
 * {@code cases.classification_failure_reason} (same channel as {@code was_fast_track}), and read
 * by cases-service to decide whether a {@code CLASSIFICATION_FAILED} case is worth auto-requeuing.
 *
 * <p>Only {@link #INFRASTRUCTURE} is: a transient outage of a dependency (rules-service down, a
 * timeout) self-resolves once the dependency comes back, so the case can be requeued without a
 * human deciding anything different. {@link #OTHER} covers everything else — a bug, bad input, an
 * unexpected exception — where retrying automatically would just fail again the same way.
 */
public enum ClassificationFailureReason {
    INFRASTRUCTURE,
    OTHER
}
