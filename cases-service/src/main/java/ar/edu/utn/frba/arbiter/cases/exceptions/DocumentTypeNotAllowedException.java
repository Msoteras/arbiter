package ar.edu.utn.frba.arbiter.cases.exceptions;

/** The insured tried to upload a document only the insurer attaches (a provider's report). */
public class DocumentTypeNotAllowedException extends RuntimeException {

    public DocumentTypeNotAllowedException(String type) {
        super("El documento '" + type + "' no se puede adjuntar a una denuncia.");
    }
}
