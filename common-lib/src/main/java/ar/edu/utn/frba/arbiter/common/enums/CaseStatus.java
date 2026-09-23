package ar.edu.utn.frba.arbiter.common.enums;

import java.util.List;

/** Lifecycle state of a case, owned by cases-service. Spanish labels are a frontend concern. */
public enum CaseStatus {
    PENDING_CLASSIFICATION,
    PENDING_ANALYST_REVIEW,
    CLASSIFICATION_FAILED,
    /**
     * Required documents are missing and the insured has to upload them (which resets the case to
     * PENDING_CLASSIFICATION). Deliberately NOT in the analyst's queue.
     */
    AWAITING_DOCUMENTATION,
    /** Waiting for an external expert's report. Not final: goes back to {@link #PENDING_ANALYST_REVIEW}. */
    PENDING_EXPERT_REPORT,
    PENDING_REPAIR,
    APPROVED,
    REJECTED,
    /**
     * Final, system-driven: closed by {@code LapseSweepScheduler} after 18 months stuck in
     * {@link #AWAITING_DOCUMENTATION} with no movement from the insured. Not the legal prescription.
     */
    LAPSED;

    /**
     * States where the art. 56 deadline is frozen because the case waits on someone outside the
     * insurer. Shared here because cases-service freezes the deadline with it and reports-service
     * splits resolution time with it; two separate lists would drift apart.
     */
    public static List<CaseStatus> pausingTheTerm() {
        return List.of(AWAITING_DOCUMENTATION, PENDING_EXPERT_REPORT, PENDING_REPAIR);
    }
}
