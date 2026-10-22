package ar.edu.utn.frba.arbiter.auth.exceptions;

public class InsuredEmailTakenException extends RuntimeException {

    public InsuredEmailTakenException() {
        super("El email ya pertenece a otro asegurado.");
    }
}
