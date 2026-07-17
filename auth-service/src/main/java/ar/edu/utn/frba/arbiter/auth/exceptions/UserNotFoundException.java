package ar.edu.utn.frba.arbiter.auth.exceptions;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("Usuario " + id + " no encontrado");
    }
}
