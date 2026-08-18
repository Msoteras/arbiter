package ar.edu.utn.frba.arbiter.rules.exceptions;

/** A branch with that name already exists (the catalog is global and the name is unique). */
public class BranchNameConflictException extends RuntimeException {

    public BranchNameConflictException(String name) {
        super("Ya existe un ramo con el nombre '" + name + "'");
    }
}
