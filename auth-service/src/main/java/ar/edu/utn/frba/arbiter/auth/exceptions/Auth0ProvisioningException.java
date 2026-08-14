package ar.edu.utn.frba.arbiter.auth.exceptions;

/** An operation on the user in Auth0 (Management API) failed during creation or deletion. */
public class Auth0ProvisioningException extends RuntimeException {
    public Auth0ProvisioningException(String operation, String email, Throwable cause) {
        super("No se pudo " + operation + " el usuario en Auth0: " + email
                + (cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""), cause);
    }
}
