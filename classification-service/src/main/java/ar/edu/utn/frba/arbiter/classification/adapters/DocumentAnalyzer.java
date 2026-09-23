package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;

public interface DocumentAnalyzer {

    /** Never throws for an unreadable document: one bad file must not sink the whole classification. */
    DocumentExtraction extract(byte[] content, String contentType);
}
