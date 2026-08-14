package ar.edu.utn.frba.arbiter.auth.exceptions;

/** The email's domain has no MX records — it can't receive real mail. */
public class InvalidEmailDomainException extends RuntimeException {
    public InvalidEmailDomainException(String email) {
        super("El dominio del email no parece real (sin registros MX): " + email);
    }
}
