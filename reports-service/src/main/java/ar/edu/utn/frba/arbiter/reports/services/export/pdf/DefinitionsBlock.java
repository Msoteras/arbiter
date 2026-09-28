package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

public record DefinitionsBlock(String heading, List<Definition> definitions) implements Block {

    private static final float TERM_FRACTION = 0.34f;
    private static final float LEADING = 1.35f;
    private static final float ROW_GAP = ReportTheme.SPACE_3;

    public record Definition(String term, String description) {}

    @Override
    public float height(float width) throws IOException {
        float total = heading == null ? 0 : ReportTheme.SECTION + ReportTheme.SPACE_3;
        for (Definition definition : definitions) {
            total += rowHeight(definition, width) + ROW_GAP;
        }
        return total - ROW_GAP;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float y = top;
        if (heading != null) {
            canvas.text(x, y - ReportTheme.SECTION, heading, PdfCanvas.Weight.BOLD,
                    ReportTheme.SECTION, ReportTheme.INK);
            y -= ReportTheme.SECTION + ReportTheme.SPACE_3;
        }

        float termWidth = width * TERM_FRACTION;
        float descriptionX = x + termWidth;
        for (Definition definition : definitions) {
            float termY = y;
            for (String line : termLines(definition, termWidth)) {
                canvas.text(x, termY - ReportTheme.BODY, line, PdfCanvas.Weight.BOLD,
                        ReportTheme.BODY, ReportTheme.INK);
                termY -= ReportTheme.BODY * LEADING;
            }
            float descriptionY = y;
            for (String line : descriptionLines(definition, width)) {
                canvas.text(descriptionX, descriptionY - ReportTheme.BODY, line,
                        PdfCanvas.Weight.REGULAR, ReportTheme.BODY, ReportTheme.INK_SOFT);
                descriptionY -= ReportTheme.BODY * LEADING;
            }
            y -= rowHeight(definition, width) + ROW_GAP;
        }
    }

    private float rowHeight(Definition definition, float width) throws IOException {
        int lines = Math.max(termLines(definition, width * TERM_FRACTION).size(),
                descriptionLines(definition, width).size());
        return lines * ReportTheme.BODY * LEADING;
    }

    private static List<String> termLines(Definition definition, float termWidth) throws IOException {
        return PdfCanvas.wrap(definition.term(), PdfCanvas.Weight.BOLD, ReportTheme.BODY,
                termWidth - ReportTheme.SPACE_2);
    }

    private static List<String> descriptionLines(Definition definition, float width)
            throws IOException {
        return PdfCanvas.wrap(definition.description(), PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                width * (1 - TERM_FRACTION));
    }
}
