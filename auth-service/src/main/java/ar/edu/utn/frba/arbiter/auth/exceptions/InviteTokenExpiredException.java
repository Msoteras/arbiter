package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * Pasaron más de 48hs desde que se generó el token. Compartida entre activación (pedile al
 * referente que te dé de alta de nuevo) y reset de contraseña (pedí un link nuevo).
 */
public class InviteTokenExpiredException extends RuntimeException {
    public InviteTokenExpiredException() {
        super("El link venció.");
    }
}
