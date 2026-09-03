package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Lifecycle state of an Expediente (case), owned by cases-service. Constants are in English
 * (code convention); the Spanish display label is a frontend concern (see estado.ts).
 */
public enum CaseStatus {
    /** Registered; classification requested but not yet available. Nobody acts; the system is working. */
    PENDING_CLASSIFICATION,
    /** Classification available; waiting for the analyst's decision. The ANALYST acts. */
    PENDING_ANALYST_REVIEW,
    /** Classification failed after exhausting retry attempts. Handling to be defined with the analysts. */
    CLASSIFICATION_FAILED,
    /**
     * Classification came back FALTA_DOCUMENTACION: required documents are missing. The INSURED
     * acts (uploads them via POST /cases/{id}/documents, which resets to PENDING_CLASSIFICATION).
     * Deliberately NOT in the analyst's queue.
     */
    AWAITING_DOCUMENTATION,
    /**
     * The analyst derived the case to an external expert and is waiting for their report. Not
     * final and not a verdict: the case comes back to {@link #PENDING_ANALYST_REVIEW} once the
     * report is in, and the analyst still decides. Nobody inside Arbiter acts meanwhile — the
     * expert is outside the system and answers by email.
     */
    PENDING_EXPERT_REPORT,
    /** Final: the analyst approved the claim (set by the decision endpoint). */
    APPROVED,
    /** Final: the analyst rejected the claim (set by the decision endpoint). */
    REJECTED,
    /**
     * Final: closed by {@code LapseSweepScheduler} after 18 months with no movement from the
     * insured since the denuncia while stuck in {@link #AWAITING_DOCUMENTATION} — "inacción del
     * asegurado ante requerimientos" (regla interna, distinta de la prescripción legal). SYSTEM-driven,
     * never chosen by an analyst.
     */
    LAPSED
}
