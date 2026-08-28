package ar.edu.utn.frba.arbiter.classification.dto;

import java.util.List;

/**
 * Result of checking whether a claim image is already published on the web.
 *
 * <p>Deliberately built from {@code fullMatchingImages}, {@code partialMatchingImages} and
 * {@code pagesWithMatchingImages} ONLY. Vision's {@code visuallySimilarImages} is excluded on
 * purpose: it returns "other pictures of similar-looking things" (every phone photo resembles
 * other phone photos), so using it would fire on legitimate claims — validated during the PoC,
 * where a genuine self-taken photo returned 10 visually-similar hits and zero real matches.
 *
 * @param fullMatches    exact copies of the image found on the web
 * @param partialMatches cropped/resized/edited variants
 * @param pages          pages where the image appears, most relevant first
 * @param bestGuessLabel what Vision thinks the image depicts — useful to cross-check against
 *                       the declared insured item
 */
public record WebImageMatch(
        int fullMatches,
        int partialMatches,
        List<MatchedPage> pages,
        String bestGuessLabel
) {

    public record MatchedPage(String url, String title) {}

    public static WebImageMatch none() {
        return new WebImageMatch(0, 0, List.of(), null);
    }

    /** True when the image was found on the web at all — any of the three real signals hit. */
    public boolean found() {
        return fullMatches > 0 || partialMatches > 0 || !pages.isEmpty();
    }
}
