package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * The user is still referenced by recorded activity (case history, messages, settlements, rules
 * they created...), so deleting them would break that trail. Nothing was changed.
 */
public class UserHasActivityException extends RuntimeException {
    public UserHasActivityException(Throwable cause) {
        super("El usuario tiene actividad registrada y no se puede eliminar", cause);
    }
}
