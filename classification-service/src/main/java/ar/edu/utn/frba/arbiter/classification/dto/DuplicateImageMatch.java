package ar.edu.utn.frba.arbiter.classification.dto;

public record DuplicateImageMatch(
        Long matchedCaseId,
        String matchedAttachmentLabel,
        String matchedFilename,
        double similarity
) {}
