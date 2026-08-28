package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * The token's validity window ran out (48h for invites, 2h for password resets — see
 * UserService). Shared between activation (ask the referente to re-invite you) and
 * password reset (request a new link).
 */
public class InviteTokenExpiredException extends RuntimeException {
    public InviteTokenExpiredException() {
        super("El link venció.");
    }
}
