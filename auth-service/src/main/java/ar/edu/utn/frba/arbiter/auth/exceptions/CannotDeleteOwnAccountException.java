package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Keeps a referente from deleting themselves and losing access. */
public class CannotDeleteOwnAccountException extends RuntimeException {
    public CannotDeleteOwnAccountException() {
        super("No podés eliminar tu propia cuenta");
    }
}
