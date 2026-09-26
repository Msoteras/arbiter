package ar.edu.utn.frba.arbiter.common.dto;

import java.util.List;

/**
 * @param webSearchesPerformed tells "not searched" apart from "searched and found nothing"
 * @param imageConsent         kept for Ley 25.326 audits; null means not recorded (older reports),
 *                             never a refusal
 */
public record ImageForensicReport(
        int imagesAnalyzed,
        int webSearchesPerformed,
        Boolean imageConsent,
        List<ImageFinding> findings
) {

    /**
     * @param documentType the {@code case_documents.type}, unique per case: what the UI joins on
     * @param webFinding   null when the web was not searched for this image
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
