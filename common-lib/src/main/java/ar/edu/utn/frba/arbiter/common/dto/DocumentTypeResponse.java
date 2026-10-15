package ar.edu.utn.frba.arbiter.common.dto;

/**
 * A row of {@code arbiter_common.document_type} as rules-service serves it, to the frontend and to
 * classification-service, which names documents with it in the fraud score's findings.
 */
public record DocumentTypeResponse(String code, String name) {}
