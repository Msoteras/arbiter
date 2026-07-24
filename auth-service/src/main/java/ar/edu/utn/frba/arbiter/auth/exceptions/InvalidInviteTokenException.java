package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * El token no matchea ningún usuario (ya usado, o nunca existió). Compartida entre el flujo de
 * activación (Fase 3) y el de reset de contraseña — ambos reusan las mismas columnas de token.
 */
public class InvalidInviteTokenException extends RuntimeException {
    public InvalidInviteTokenException() {
        super("El link no es válido.");
    }
}
