package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;

import java.util.List;

public interface DocumentAnalyzer {

    /**
     * Sends an attachment (photo of an invoice, repair quote, police report, or of the insured
     * item itself) to the vision model and returns what it reads on it, plus any visible sign
     * that the document was manipulated.
     *
     * <p>Never throws for a document it cannot read: an unreadable attachment degrades to an
     * extraction saying so. One bad file must not sink the whole classification.
     *
     * @param claimCauses the branch's claim cause names: the only values
     *                    {@code describedClaimCause} can take. Empty means the catalog couldn't be
     *                    read, and the field comes back null.
     */
    DocumentExtraction extract(byte[] content, String contentType, List<String> claimCauses);
}
