package ar.edu.utn.frba.arbiter.auth.exceptions;

/** El dominio del email no tiene registros MX — no puede recibir correo real. */
public class InvalidEmailDomainException extends RuntimeException {
    public InvalidEmailDomainException(String email) {
        super("El dominio del email no parece real (sin registros MX): " + email);
    }
}
