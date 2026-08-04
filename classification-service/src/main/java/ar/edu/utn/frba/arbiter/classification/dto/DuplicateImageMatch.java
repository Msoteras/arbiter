package ar.edu.utn.frba.arbiter.classification.dto;

/**
 * An image already in the system that resembles the one being analysed.
 *
 * @param matchedCaseId          the previous claim the match belongs to
 * @param matchedDocumentId      the exact {@code case_documents} row that matched — persisted on
 *                               {@code image_analysis.similar_document_id}, so the finding points
 *                               at a file and not just at a case
 * @param matchedAttachmentLabel that document's type (invoice, item_photo, ...)
 * @param matchedFilename        that document's filename, for display
 * @param similarity             cosine similarity in [0,1]
 */
public record DuplicateImageMatch(
        Long matchedCaseId,
        Long matchedDocumentId,
        String matchedAttachmentLabel,
        String matchedFilename,
        double similarity
) {}
