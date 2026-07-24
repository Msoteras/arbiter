package ar.edu.utn.frba.arbiter.auth.exceptions;

public class UserAlreadyActiveException extends RuntimeException {
    public UserAlreadyActiveException() {
        super("Este usuario ya activó su cuenta.");
    }
}
