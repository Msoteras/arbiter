package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;

import java.util.List;

public interface DocumentAnalyzer {

    /**
     * Never throws for an unreadable document: one bad file must not sink the whole classification.
     *
     * @param claimCauses the branch's claim cause names, the only values {@code describedClaimCause}
     *                    can take; empty if the catalog couldn't be read
     */
    DocumentExtraction extract(byte[] content, String contentType, List<String> claimCauses);
}
