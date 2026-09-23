package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * {@code type} tells OCR how to read the file. {@code documentId} is the {@code case_documents} row,
 * persisted by image analysis so a duplicate match names the exact file; null when not stored.
 */
public record AttachmentDocument(Long documentId, String type, byte[] content, String contentType) {
}
