package ar.edu.utn.frba.arbiter.auth.exceptions;

/** The login envelope couldn't be opened: format, base64, wrong key or expired. */
public class InvalidEncryptedPasswordException extends RuntimeException {
    public InvalidEncryptedPasswordException() {
        super("No pudimos procesar el pedido. Recargá la página y probá de nuevo.");
    }
}
