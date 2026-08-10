package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The denounced policy belongs to a different insured. Both references resolve on their own —
 * that is why this is not an {@link UnresolvedCaseReferenceException} — but the combination does
 * not exist: policy and insured were being resolved independently, so pairing one person's DNI
 * with another person's policy went through.
 *
 * <p>422, consistent with the other "well-formed request whose content does not hold" cases.
 * Checked regardless of role: no caller has a reason to file a claim on a policy that is not the
 * insured's.
 */
public class PolicyInsuredMismatchException extends RuntimeException {

    public PolicyInsuredMismatchException(String policyNumber) {
        super("Policy '" + policyNumber + "' does not belong to the insured in this denuncia");
    }
}
