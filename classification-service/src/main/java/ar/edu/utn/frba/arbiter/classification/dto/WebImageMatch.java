package ar.edu.utn.frba.arbiter.classification.dto;

import java.util.List;

/**
 * Built only from full/partial matches and matching pages. Vision's {@code visuallySimilarImages} is
 * excluded on purpose: every phone photo resembles other phone photos, so it fires on genuine claims.
 *
 * @param partialMatches cropped/resized/edited variants
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

    public boolean found() {
        return fullMatches > 0 || partialMatches > 0 || !pages.isEmpty();
    }
}
