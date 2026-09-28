package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.List;

public record BarListBlock(String label, List<Bar> bars, String note) implements Block {

    private static final float LABEL_FRACTION = 0.42f;
    private static final float VALUE_WIDTH = 52;
    private static final float TRACK_HEIGHT = 7;
    private static final float ROW_GAP = ReportTheme.SPACE_2;
    private static final float LABEL_LEADING = 1.2f;
    private static final int MAX_LABEL_LINES = 2;

    public record Bar(String label, long count, Double share, Rgb color) {}

    @Override
    public float height(float width) throws IOException {
        float total = ReportTheme.LABEL + ReportTheme.SPACE_3;
        for (Bar bar : bars) {
            total += rowHeight(bar, width) + ROW_GAP;
        }
        if (note != null) {
            total += ReportTheme.SPACE_1 + noteLines(width).size() * ReportTheme.NOTE * 1.3f;
        }
        return total;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float labelWidth = width * LABEL_FRACTION;
        float trackX = x + labelWidth + ReportTheme.SPACE_2;
        float trackWidth = width - labelWidth - ReportTheme.SPACE_2 - VALUE_WIDTH
                - ReportTheme.SPACE_2;

        canvas.label(x, top - ReportTheme.LABEL, label, ReportTheme.MUTED);
        float y = top - ReportTheme.LABEL - ReportTheme.SPACE_3;

        for (Bar bar : bars) {
            float rowHeight = rowHeight(bar, width);
            float textY = y;
            for (String line : labelLines(bar, labelWidth)) {
                canvas.text(x, textY - ReportTheme.BODY, line, PdfCanvas.Weight.REGULAR,
                        ReportTheme.BODY, ReportTheme.INK_SOFT);
                textY -= ReportTheme.BODY * LABEL_LEADING;
            }

            float trackY = y - ReportTheme.BODY * LABEL_LEADING / 2 - TRACK_HEIGHT / 2;
            canvas.panel(trackX, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT / 2,
                    ReportTheme.BORDER_SUBTLE, null);
            if (bar.share() != null && bar.share() > 0) {
                float filled = Math.max(TRACK_HEIGHT, (float) (trackWidth * bar.share()));
                canvas.panel(trackX, trackY, filled, TRACK_HEIGHT, TRACK_HEIGHT / 2, bar.color(), null);
            }

            String value = bar.share() == null
                    ? String.valueOf(bar.count())
                    : "%d · %s".formatted(bar.count(), percent(bar.share()));
            canvas.textRight(x + width, trackY + 1, value, PdfCanvas.Weight.BOLD, ReportTheme.NOTE,
                    bar.count() == 0 ? ReportTheme.MUTED : ReportTheme.INK);

            y -= rowHeight + ROW_GAP;
        }

        if (note != null) {
            y -= ReportTheme.SPACE_1;
            for (String line : noteLines(width)) {
                canvas.text(x, y - ReportTheme.NOTE, line, PdfCanvas.Weight.REGULAR,
                        ReportTheme.NOTE, ReportTheme.MUTED);
                y -= ReportTheme.NOTE * 1.3f;
            }
        }
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    private float rowHeight(Bar bar, float width) throws IOException {
        return Math.max(labelLines(bar, width * LABEL_FRACTION).size() * ReportTheme.BODY
                * LABEL_LEADING, TRACK_HEIGHT);
    }

    private static List<String> labelLines(Bar bar, float labelWidth) throws IOException {
        return PdfCanvas.wrap(bar.label(), PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                labelWidth - ReportTheme.SPACE_2, MAX_LABEL_LINES);
    }

    private List<String> noteLines(float width) throws IOException {
        return PdfCanvas.wrap(note, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, width);
    }
}
