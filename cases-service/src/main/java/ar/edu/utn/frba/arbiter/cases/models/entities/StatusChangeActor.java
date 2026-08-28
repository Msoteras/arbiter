package ar.edu.utn.frba.arbiter.cases.models.entities;

/**
 * Who drove a case status transition. Kept structured (not just free text) so the audit trail
 * can answer "who moved this case" — the insured, the system, or an analyst.
 */
public enum StatusChangeActor {
    /** Automated: classification polling, retry exhaustion, scheduled jobs. */
    SYSTEM,
    /** The insured: registered the claim, uploaded additional documentation. */
    INSURED,
    /** An analyst: approved or rejected the claim (decision endpoint — pending auth). */
    ANALYST,
    /** An insurer referent (REFERENTE_ASEGURADORA): assigned/reassigned/released a case they don't own. */
    REFERENT
}
