package ar.edu.utn.frba.arbiter.rules.exceptions;

/** Ya existe un ramo con ese nombre (el catálogo es global y el nombre es único). */
public class BranchNameConflictException extends RuntimeException {

    public BranchNameConflictException(String name) {
        super("Ya existe un ramo con el nombre '" + name + "'");
    }
}
