package ar.edu.utn.frba.arbiter.common.dto;

import java.util.List;

/**
 * Structured result of the image-fraud analysis, exposed to the analyst UI (analyst-only —
 * the insured never sees this). Grouped <b>per image</b> so the frontend can render each
 * attachment next to its own findings.
 *
 * <p>Null on the {@link ClaimResponse} when no analysis ran (Fast Track, or a claim with no
 * image attachments). {@code webSearchesPerformed} lets the UI tell "the web wasn't searched"
 * apart from "searched and found nothing".
 *
 * @param imagesAnalyzed       image attachments that went through the analysis
 * @param webSearchesPerformed how many were escalated to the external web search
 * @param findings             one entry per analyzed image, in attachment order
 */
public record ImageForensicReport(
        int imagesAnalyzed,
        int webSearchesPerformed,
        List<ImageFinding> findings
) {

    /**
     * @param label           unique label within the report (e.g. "item_photo-0")
     * @param documentType    the {@code case_documents.type} this image was uploaded as
     *                        ("item_photo", "invoice", ...). Unique per case, so it's what the
     *                        UI joins on to fetch the actual file. Deliberately not a filename:
     *                        the analysis works off {@code AttachmentDocument}, which carries the
     *                        type and not the original name.
     * @param internalMatches matches against attachments of previous claims (empty if none)
     * @param webFinding      web-search result, or null when the web wasn't searched for this image
     *                        (no internal match was needed, or the external search is disabled/failed)
     */
    public record ImageFinding(
            String label,
            String documentType,
            List<InternalMatch> internalMatches,
            WebFinding webFinding
    ) {}

    /**
     * @param matchedCaseId       the previous claim whose attachment this image resembles
     * @param matchedDocumentType that attachment's type — what the UI joins on to fetch the file
     *                            and show both images side by side
     * @param matchedFilename     that attachment's original filename, for display only
     * @param similarity          cosine similarity in [0,1]
     */
    public record InternalMatch(
            Long matchedCaseId,
            String matchedDocumentType,
            String matchedFilename,
            double similarity
    ) {}

    /**
     * @param fullMatches     exact copies found on the web
     * @param partialMatches  cropped/resized/edited variants
     * @param pages           pages where the image appears (URLs the analyst can open)
     * @param bestGuessLabel  what the vision service thinks the image depicts
     */
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
