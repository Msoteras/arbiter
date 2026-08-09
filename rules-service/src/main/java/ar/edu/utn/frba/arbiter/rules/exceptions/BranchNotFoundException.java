package ar.edu.utn.frba.arbiter.rules.exceptions;

/** The referente tried to configure a rule for a rama that doesn't exist in arbiter_common. */
public class BranchNotFoundException extends RuntimeException {

    public BranchNotFoundException(Long branchId) {
        super("No existe el ramo con id " + branchId);
    }
}
