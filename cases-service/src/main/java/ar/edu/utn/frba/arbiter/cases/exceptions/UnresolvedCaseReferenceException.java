package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * A policy, branch, claim cause or insured the tenant doesn't have. 422 rather than 404: the request
 * is well formed, its content just doesn't resolve.
 */
public class UnresolvedCaseReferenceException extends RuntimeException {

    public UnresolvedCaseReferenceException(String reference, String value) {
        super("No " + reference + " matching '" + value + "' in this insurer's data");
    }
}
