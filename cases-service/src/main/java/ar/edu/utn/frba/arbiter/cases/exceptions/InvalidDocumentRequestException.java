package ar.edu.utn.frba.arbiter.cases.exceptions;

/** A documentation request the analyst can't send as written. */
public class InvalidDocumentRequestException extends RuntimeException {

    private InvalidDocumentRequestException(String message) {
        super(message);
    }

    public static InvalidDocumentRequestException notRequestable(String type) {
        return new InvalidDocumentRequestException(
                "El documento '" + type + "' no se puede pedir: no es del ramo, ya está subido o ya está pedido.");
    }

    public static InvalidDocumentRequestException fastTrack() {
        return new InvalidDocumentRequestException(
                "El expediente entró en Fast Track: alcanza con la documentación que tiene para resolverlo.");
    }

    public static InvalidDocumentRequestException otherWithoutDetails() {
        return new InvalidDocumentRequestException(
                "Para pedir otro documento hay que indicar qué documento es y por qué se pide.");
    }
}
