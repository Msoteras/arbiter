package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Lifecycle state of an Expediente (case), owned by cases-service. Constants are in English
 * (code convention); the Spanish display label is a frontend concern (see estado.ts).
 */
public enum CaseStatus {
    /** Registered; classification requested but not yet available. */
    PENDING_CLASSIFICATION,
    /** Classification available; waiting for the analyst's decision. */
    PENDING_ANALYST_REVIEW,
    /** Classification failed after exhausting retry attempts. */
    CLASSIFICATION_FAILED
}
