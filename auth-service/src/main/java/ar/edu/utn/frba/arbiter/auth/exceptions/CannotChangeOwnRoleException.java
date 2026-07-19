package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Evita que un referente se autodegrade o se bloquee sin querer. */
public class CannotChangeOwnRoleException extends RuntimeException {
    public CannotChangeOwnRoleException() {
        super("No podés cambiar tu propio rol");
    }
}
