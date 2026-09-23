package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Already used or never existed. Shared by the activation and password reset flows. */
public class InvalidInviteTokenException extends RuntimeException {
    public InvalidInviteTokenException() {
        super("El link no es válido.");
    }
}
