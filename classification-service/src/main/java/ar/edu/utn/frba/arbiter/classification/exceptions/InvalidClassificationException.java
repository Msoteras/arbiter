package ar.edu.utn.frba.arbiter.classification.exceptions;

public class InvalidClassificationException extends RuntimeException {

    public InvalidClassificationException(String message) {
        super(message);
    }

    public InvalidClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
