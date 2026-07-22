package ar.edu.utn.frba.arbiter.cases.exceptions;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(Long caseId, Long documentId) {
        super("Document " + documentId + " not found for case " + caseId);
    }
}
