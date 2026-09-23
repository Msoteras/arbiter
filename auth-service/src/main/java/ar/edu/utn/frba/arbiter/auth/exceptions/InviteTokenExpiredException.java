package ar.edu.utn.frba.arbiter.auth.exceptions;

public class InviteTokenExpiredException extends RuntimeException {
    public InviteTokenExpiredException() {
        super("El link venció.");
    }
}
