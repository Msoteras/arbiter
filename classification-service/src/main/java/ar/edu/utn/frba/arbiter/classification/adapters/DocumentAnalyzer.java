package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;

public interface DocumentAnalyzer {

    /**
     * Sends an attachment (photo of an invoice, repair quote, police report, or of the insured
     * item itself) to the vision model and returns what it reads on it, plus any visible sign
     * that the document was manipulated.
     *
     * <p>Never throws for a document it cannot read: an unreadable attachment degrades to an
     * extraction saying so. One bad file must not sink the whole classification.
     */
    DocumentExtraction extract(byte[] content, String contentType);
}
