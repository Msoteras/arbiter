package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * The token doesn't match any user (already used, or never existed). Shared between the
 * activation flow (Phase 3) and the password reset flow — both reuse the same token columns.
 */
public class InvalidInviteTokenException extends RuntimeException {
    public InvalidInviteTokenException() {
        super("El link no es válido.");
    }
}
