package ar.edu.utn.frba.arbiter.reports.dto;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record ReportBranding(String insurerName, String insurerLegalName) {

    public static final ReportBranding UNKNOWN = new ReportBranding("Aseguradora", "Aseguradora");

    private static final int MONOGRAM_LETTERS = 2;

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

    private static String letters(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("[^\\p{Alpha}]", "");
    }
}
