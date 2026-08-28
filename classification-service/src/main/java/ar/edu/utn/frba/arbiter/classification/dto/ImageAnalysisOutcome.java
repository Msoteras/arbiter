package ar.edu.utn.frba.arbiter.classification.dto;

import java.util.List;

/**
 * What came out of analysing one image: the duplicates found, plus the id of the
 * {@code image_analysis} row they were recorded on.
 *
 * <p>The id matters because the analysis happens in two passes — the internal comparison
 * first, and an external web search only if that came back empty. The second pass needs to
 * find the row the first one wrote.
 *
 * @param analysisId null when the image had no stored document to anchor to (isolated
 *                   classification), in which case nothing was persisted
 */
public record ImageAnalysisOutcome(Long analysisId, List<DuplicateImageMatch> duplicates) {

    public static ImageAnalysisOutcome none() {
        return new ImageAnalysisOutcome(null, List.of());
    }
}
