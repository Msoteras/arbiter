package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Evita que un referente se elimine a sí mismo y se quede sin acceso. */
public class CannotDeleteOwnAccountException extends RuntimeException {
    public CannotDeleteOwnAccountException() {
        super("No podés eliminar tu propia cuenta");
    }
}
