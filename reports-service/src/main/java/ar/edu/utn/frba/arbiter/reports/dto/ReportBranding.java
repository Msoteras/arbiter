package ar.edu.utn.frba.arbiter.reports.dto;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Who an exported report belongs to, for the branded header every PDF carries.
 *
 * <p>The insurer never uploads an image. The mark is a monogram derived from its own name, so a
 * newly registered tenant is branded the moment its {@code arbiter_common.insurer} row exists, with
 * nothing to configure, nothing to redeploy, and no file that can come back the wrong size and push
 * the header out of the page.
 *
 * @param insurerName      the short, commercial name; the monogram is built from it
 * @param insurerLegalName what the header prints, because a report leaves the company
 */
public record ReportBranding(String insurerName, String insurerLegalName) {

    /**
     * Header for a report whose insurer could not be read. The identity is decoration on top of the
     * figures: losing it must not cost the referent the export.
     */
    public static final ReportBranding UNKNOWN = new ReportBranding("Aseguradora", "Aseguradora");

    private static final int MONOGRAM_LETTERS = 2;

    /**
     * Two letters, so every insurer's box is the same width whatever its name: the initials of the
     * first two words, or the first two letters when the name is a single word ("BBVA" → "BB",
     * "Provincia Seguros" → "PS"). Accents are folded away because the mark is drawn in the PDF's
     * WinAnsi standard fonts.
     *
     * @return empty when the name carries no letters at all, which leaves the box empty rather than
     *         printing a stand-in glyph
     */
    public String monogram() {
        List<String> words = Arrays.stream(insurerName.split("\\s+"))
                .map(ReportBranding::letters)
                .filter(word -> !word.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return "";
        }
        String initials = words.size() == 1
                ? words.getFirst()
                : words.get(0).charAt(0) + words.get(1).substring(0, 1);
        return initials.substring(0, Math.min(MONOGRAM_LETTERS, initials.length()))
                .toUpperCase(Locale.ROOT);
    }

    /** NFD splits an accented letter into its base and a combining mark, which the filter then drops. */
    private static String letters(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("[^\\p{Alpha}]", "");
    }
}
