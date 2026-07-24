package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Falló una operación sobre el usuario en Auth0 (Management API) durante el alta o la baja. */
public class Auth0ProvisioningException extends RuntimeException {
    public Auth0ProvisioningException(String operation, String email, Throwable cause) {
        super("No se pudo " + operation + " el usuario en Auth0: " + email
                + (cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""), cause);
    }
}
