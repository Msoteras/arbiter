package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.RequestedDocument;

/** {@code reason} is internal: {@link #forInsured()} drops it. */
public record RequestedDocumentResponse(String type, String description, String reason, boolean fulfilled) {

    public static RequestedDocumentResponse from(RequestedDocument document) {
        return new RequestedDocumentResponse(
                document.type(), document.description(), document.reason(), document.fulfilled());
    }

    public RequestedDocumentResponse forInsured() {
        return new RequestedDocumentResponse(type, description, null, fulfilled);
    }
}
