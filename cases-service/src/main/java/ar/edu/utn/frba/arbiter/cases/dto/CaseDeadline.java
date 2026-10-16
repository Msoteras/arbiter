package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Where a case stands against its running art. 56 term, as the listing filters it. Paused and closed
 * cases are never in either: their deadline is frozen or no longer applies.
 */
public enum CaseDeadline {
    /** Due within {@code DeadlinePriority.WATCH_DAYS}, overdue included: the deadline indicator is on. */
    DUE_SOON,
    /** Already past the deadline. */
    OVERDUE
}
