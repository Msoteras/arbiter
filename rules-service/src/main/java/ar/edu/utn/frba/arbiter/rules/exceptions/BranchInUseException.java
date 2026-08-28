package ar.edu.utn.frba.arbiter.rules.exceptions;

/** A branch that still has claim causes, coverages or rules attached can't be deleted. */
public class BranchInUseException extends RuntimeException {

    public BranchInUseException(Long branchId) {
        super("No se puede eliminar el ramo " + branchId
                + ": todavía tiene hechos generadores, coberturas o reglas asociadas");
    }
}
