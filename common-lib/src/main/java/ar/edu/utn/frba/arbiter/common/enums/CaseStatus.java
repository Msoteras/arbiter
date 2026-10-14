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
    LAPSED,
    /**
     * Final: the analyst closed it because the insured abandoned the claim. Nobody decided it, so it
     * stays out of the approval and rejection rates.
     */
    WITHDRAWN,
    /**
     * Final: the claim never should have existed (a duplicate, the wrong policy). Closed by the
     * analyst, out of the rates like {@link #WITHDRAWN}.
     */
    VOIDED,
    /**
     * Final: rejected because the claim prescribed (art. 58, Ley 17.418), not on its merits. The
     * analyst decides it; the insured sees it as a rejection.
     */
    PRESCRIBED;

    /**
     * States where the art. 56 deadline is frozen because the case waits on someone outside the
     * insurer. Shared here because cases-service freezes the deadline with it and reports-service
     * splits resolution time with it; two separate lists would drift apart.
     */
    public static List<CaseStatus> pausingTheTerm() {
        return List.of(AWAITING_DOCUMENTATION, PENDING_EXPERT_REPORT, PENDING_REPAIR);
    }

    /**
     * The statuses that close a case: they take no event until it is reopened. Same set as
     * {@code case_status.is_final}; here for the code that branches on the enum in more than one module.
     */
    public static List<CaseStatus> terminal() {
        return List.of(APPROVED, REJECTED, LAPSED, WITHDRAWN, VOIDED, PRESCRIBED);
    }

    /**
     * The terminal statuses someone reached with a verdict. Rates and resolution times are measured
     * over these only: a lapsed, withdrawn or voided case measures nobody's decision.
     */
    public static List<CaseStatus> decided() {
        return List.of(APPROVED, REJECTED, PRESCRIBED);
    }
}
