package ar.edu.utn.frba.arbiter.auth.exceptions;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("Ya existe un usuario con el email " + email);
    }
}
