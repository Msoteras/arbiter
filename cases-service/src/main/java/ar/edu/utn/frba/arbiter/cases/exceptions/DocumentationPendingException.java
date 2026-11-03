package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The classification found documents missing and the analyst hasn't asked for them yet: the model
 * didn't run, so there is nothing to decide or refer on.
 */
public class DocumentationPendingException extends RuntimeException {

    public DocumentationPendingException(Long caseId) {
        super("Al expediente #" + caseId + " le falta documentación: primero pedísela al asegurado.");
    }
}
