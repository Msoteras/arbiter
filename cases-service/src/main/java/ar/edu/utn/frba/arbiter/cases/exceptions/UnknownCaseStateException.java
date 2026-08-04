package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The status catalog has no row for a {@code CaseStatus} the state machine uses. Not a user
 * error: it means the common schema was seeded incomplete, so the whole lifecycle is broken.
 */
public class UnknownCaseStateException extends RuntimeException {

    public UnknownCaseStateException(String statusName) {
        super("No case_status row for '" + statusName + "' — the common schema seed is incomplete");
    }
}
