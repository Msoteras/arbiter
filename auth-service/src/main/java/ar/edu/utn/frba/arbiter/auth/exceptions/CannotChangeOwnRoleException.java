package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Keeps a referente from demoting or locking themselves out by accident. */
public class CannotChangeOwnRoleException extends RuntimeException {
    public CannotChangeOwnRoleException() {
        super("No podés cambiar tu propio rol");
    }
}
