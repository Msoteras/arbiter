package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * A document the insured still has to upload.
 *
 * @param type        the multipart key the upload carries it under
 * @param description what the analyst asked for; null for a catalog document, named by its code
 */
public record PendingDocumentResponse(String type, String description) {
}
