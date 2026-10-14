package ar.edu.utn.frba.arbiter.common.enums;

/**
 * The case status as the insured gets it: several {@link CaseStatus} values collapse into one, so
 * the model's classification and its failures never reach them. Which status maps to which lives in
 * {@code arbiter_common.case_status}.
 */
public enum InsuredStatus {
    UNDER_ANALYSIS,
    AWAITING_DOCUMENTATION,
    UNDER_VERIFICATION,
    UNDER_REPAIR,
    APPROVED,
    REJECTED,
    LAPSED,
    /** Withdrawn or voided: closed without a verdict, and the insured isn't told the internal reason. */
    CLOSED
}
