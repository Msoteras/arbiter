package ar.edu.utn.frba.arbiter.rules.exceptions;

/** No se puede borrar un ramo que todavía tiene hechos generadores, coberturas o reglas asociadas. */
public class BranchInUseException extends RuntimeException {

    public BranchInUseException(Long branchId) {
        super("No se puede eliminar el ramo " + branchId
                + ": todavía tiene hechos generadores, coberturas o reglas asociadas");
    }
}
