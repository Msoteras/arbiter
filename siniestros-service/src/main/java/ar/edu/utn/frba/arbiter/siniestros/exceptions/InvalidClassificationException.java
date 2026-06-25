package ar.edu.utn.frba.arbiter.siniestros.exceptions;

public class InvalidClassificationException extends RuntimeException {

    public InvalidClassificationException(String message) {
        super(message);
    }

    public InvalidClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
