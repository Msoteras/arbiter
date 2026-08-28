package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class MultipartDocumentMapper {

    /**
     * For callers with no stored documents behind the request — the isolated-classification
     * endpoint analyses uploaded files that were never persisted as {@code case_documents}.
     */
    public List<AttachmentDocument> toAttachmentDocuments(Map<String, MultipartFile> documents) {
        return toAttachmentDocuments(documents, null);
    }

    /**
     * @param documents   attachment parts, keyed by document type
     * @param documentIds {@code case_documents} ids under those same keys, as sent by
     *                    cases-service. Keyed by type rather than by position because
     *                    {@code case_documents} is unique on (case_id, type), so the type
     *                    identifies the row unambiguously. Null when the caller doesn't
     *                    send them.
     */
    public List<AttachmentDocument> toAttachmentDocuments(
            Map<String, MultipartFile> documents, Map<String, Long> documentIds) {
        if (documents == null) {
            return List.of();
        }
        Map<String, Long> ids = documentIds != null ? documentIds : Map.of();
        return documents.entrySet().stream()
                .map(entry -> readAttachment(entry.getKey(), entry.getValue(), ids.get(entry.getKey())))
                .toList();
    }

    private AttachmentDocument readAttachment(String type, MultipartFile file, Long documentId) {
        try {
            return new AttachmentDocument(documentId, type, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not read document '" + type + "'", e);
        }
    }
}
