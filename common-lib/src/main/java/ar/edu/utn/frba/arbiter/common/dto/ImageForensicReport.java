package ar.edu.utn.frba.arbiter.common.dto;

import java.util.List;

/**
 * Image-fraud analysis for the analyst UI (analyst-only), grouped per image.
 *
 * @param webSearchesPerformed lets the UI tell "not searched" from "searched and found nothing"
 * @param imageConsent         recorded so a refusal is auditable under Ley 25.326 (zero searches
 *                             alone is ambiguous). Boxed: null means not recorded, as in reports
 *                             persisted before the field existed, and must not read as a refusal
 */
public record ImageForensicReport(
        int imagesAnalyzed,
        int webSearchesPerformed,
        Boolean imageConsent,
        List<ImageFinding> findings
) {

    /**
     * @param documentType the {@code case_documents.type}, unique per case: what the UI joins on to
     *                     fetch the file
     * @param webFinding   null when the web wasn't searched for this image
     */
    public record ImageFinding(
            String label,
            String documentType,
            List<InternalMatch> internalMatches,
            WebFinding webFinding
    ) {}

    /** @param similarity cosine similarity in [0,1] */
    public record InternalMatch(
            Long matchedCaseId,
            String matchedDocumentType,
            String matchedFilename,
            double similarity
    ) {}

    /** @param partialMatches cropped/resized/edited variants */
    public record WebFinding(
            int fullMatches,
            int partialMatches,
            List<Page> pages,
            String bestGuessLabel
    ) {
        public record Page(String url, String title) {}

        public boolean found() {
            return fullMatches > 0 || partialMatches > 0 || !pages.isEmpty();
        }
    }
}
