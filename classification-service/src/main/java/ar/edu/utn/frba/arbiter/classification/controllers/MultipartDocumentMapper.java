package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Converts the multipart `documents` map (key = document type) into AttachmentDocument list. */
@Component
public class MultipartDocumentMapper {

    public List<AttachmentDocument> toAttachmentDocuments(Map<String, MultipartFile> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.entrySet().stream()
                .map(entry -> readAttachment(entry.getKey(), entry.getValue()))
                .toList();
    }

    private AttachmentDocument readAttachment(String type, MultipartFile file) {
        try {
            return new AttachmentDocument(type, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not read document '" + type + "'", e);
        }
    }
}
