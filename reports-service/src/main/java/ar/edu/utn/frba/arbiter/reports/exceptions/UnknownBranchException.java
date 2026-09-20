package ar.edu.utn.frba.arbiter.reports.exceptions;

/**
 * The branch filter names a branch the catalog doesn't have. Rejected rather than ignored: a report
 * filtered by nothing reads "Ramo: Todos" while it matched no case, and states the wrong filter.
 */
public class UnknownBranchException extends RuntimeException {

    public UnknownBranchException(Long branchId) {
        super("No branch with id " + branchId);
    }
}
