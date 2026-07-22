package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;

import java.time.Instant;

public record CaseDocumentResponse(
        Long id,
        String type,
        String filename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt
) {

    public static CaseDocumentResponse from(CaseDocument doc) {
        return new CaseDocumentResponse(
                doc.getId(),
                doc.getType(),
                doc.getFilename(),
                doc.getContentType(),
                doc.getContent() != null ? doc.getContent().length : 0,
                doc.getUploadedAt()
        );
    }
}
