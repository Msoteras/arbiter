package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;

import java.time.Instant;

/**
 * @param description what the analyst asked for, for a document the catalog doesn't name
 *                    ({@code other_<n>}); null otherwise, the type names it
 */
public record CaseDocumentResponse(
        Long id,
        String type,
        String filename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        String description
) {

    public static CaseDocumentResponse from(CaseDocument doc, String description) {
        return new CaseDocumentResponse(
                doc.getId(),
                doc.getType(),
                doc.getFilename(),
                doc.getContentType(),
                doc.getContent() != null ? doc.getContent().length : 0,
                doc.getUploadedAt(),
                description
        );
    }
}
