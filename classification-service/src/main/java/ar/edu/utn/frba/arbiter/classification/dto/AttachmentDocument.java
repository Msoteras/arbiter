package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * An attachment to classify along with what it is (invoice, police report,
 * photo of the insured item, etc.) — OCR needs that type to know how to read it, and the
 * orchestrator uses it to decide whether extraction is even needed (depends on whether
 * the Fast Track gate already resolves the case without looking at any document).
 *
 * <p>{@code documentId} is the {@code case_documents} row this came from, forwarded by
 * cases-service. Image analysis persists it so a duplicate match can name the exact file it
 * matched ("this photo is the same as the item photo on case 17"), which a case id alone
 * cannot express. Nullable: the classification itself never needs it, and callers that
 * don't have a stored document (tests, ad-hoc analysis) leave it out.
 */
public record AttachmentDocument(Long documentId, String type, byte[] content, String contentType) {
}
