package ar.edu.utn.frba.arbiter.auth.exceptions;

/** El usuario ya activó su cuenta — no tiene sentido reenviarle una invitación. */
public class UserAlreadyActiveException extends RuntimeException {
    public UserAlreadyActiveException() {
        super("Este usuario ya activó su cuenta.");
    }
}
