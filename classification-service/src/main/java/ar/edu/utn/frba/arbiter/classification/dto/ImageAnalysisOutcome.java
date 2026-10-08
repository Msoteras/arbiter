package ar.edu.utn.frba.arbiter.classification.dto;

import java.util.List;

/**
 * @param analysisId the {@code image_analysis} row, which the later web-search pass updates; null
 *                   when the image had no stored document and nothing was persisted
 */
public record ImageAnalysisOutcome(Long analysisId, List<DuplicateImageMatch> duplicates, boolean comparisonFailed) {

    public ImageAnalysisOutcome(Long analysisId, List<DuplicateImageMatch> duplicates) {
        this(analysisId, duplicates, false);
    }

    public static ImageAnalysisOutcome failed() {
        return new ImageAnalysisOutcome(null, List.of(), true);
    }
}
