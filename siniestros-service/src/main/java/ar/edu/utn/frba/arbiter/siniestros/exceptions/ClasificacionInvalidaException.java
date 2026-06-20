package ar.edu.utn.frba.arbiter.siniestros.exceptions;

public class ClasificacionInvalidaException extends RuntimeException {

    public ClasificacionInvalidaException(String message) {
        super(message);
    }

    public ClasificacionInvalidaException(String message, Throwable cause) {
        super(message, cause);
    }
}
