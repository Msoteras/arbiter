package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Both references resolve on their own (unlike {@link UnresolvedCaseReferenceException}), but the
 * policy belongs to a different insured. Checked regardless of role.
 */
public class PolicyInsuredMismatchException extends RuntimeException {

    public PolicyInsuredMismatchException(String policyNumber) {
        super("Policy '" + policyNumber + "' does not belong to the insured in this denuncia");
    }
}
