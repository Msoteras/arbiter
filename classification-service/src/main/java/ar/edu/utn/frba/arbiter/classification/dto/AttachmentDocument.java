package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * An attachment to classify along with what it is (invoice, police report,
 * photo of the insured item, etc.) — OCR needs that type to know how to read it, and the
 * orchestrator uses it to decide whether extraction is even needed (depends on whether
 * the Fast Track gate already resolves the case without looking at any document).
 */
public record AttachmentDocument(String type, byte[] content, String contentType) {}
