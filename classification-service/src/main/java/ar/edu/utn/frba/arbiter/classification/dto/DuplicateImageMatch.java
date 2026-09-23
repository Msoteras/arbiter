package ar.edu.utn.frba.arbiter.classification.dto;

/** @param similarity cosine similarity in [0,1] */
public record DuplicateImageMatch(
        Long matchedCaseId,
        Long matchedDocumentId,
        String matchedAttachmentLabel,
        String matchedFilename,
        double similarity
) {}
