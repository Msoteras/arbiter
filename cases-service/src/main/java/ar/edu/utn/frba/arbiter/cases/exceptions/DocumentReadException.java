package ar.edu.utn.frba.arbiter.cases.exceptions;

public class DocumentReadException extends RuntimeException {

    public DocumentReadException(String documentType, Throwable cause) {
        super("Could not read document '" + documentType + "'", cause);
    }
}
