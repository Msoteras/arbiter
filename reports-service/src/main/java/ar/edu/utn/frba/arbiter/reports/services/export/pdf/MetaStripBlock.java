package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

/**
 * The band of labelled facts under the title: the filters the report ran with, who asked for it and
 * when. Equal columns on one tinted surface, so it reads as the document's masthead rather than as
 * content of its own.
 */
public record MetaStripBlock(List<Cell> cells) implements Block {

    private static final float PADDING = 10;
    private static final float VALUE_LEADING = 1.3f;
    private static final int MAX_VALUE_LINES = 2;

    /** @param note a second, quieter line under the value; null when there is nothing to qualify */
    public record Cell(String label, String value, String note) {

        public static Cell of(String label, String value) {
            return new Cell(label, value, null);
        }
    }

    @Override
    public float height(float width) throws IOException {
        return 2 * PADDING + ReportTheme.LABEL + ReportTheme.SPACE_2
                + valueLines(width) * ReportTheme.BODY * VALUE_LEADING
                + (hasNote() ? ReportTheme.SPACE_1 + ReportTheme.NOTE * 1.2f : 0);
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float height = height(width);
        canvas.panel(x, top - height, width, height, ReportTheme.RADIUS_CARD,
                ReportTheme.SURFACE_SUNKEN, null);

        float columnWidth = (width - 2 * PADDING) / cells.size();
        float textWidth = columnWidth - ReportTheme.SPACE_3;
        int valueLines = valueLines(width);
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            float columnX = x + PADDING + i * columnWidth;

            float y = top - PADDING;
            canvas.label(columnX, y - ReportTheme.LABEL,
                    PdfCanvas.wrapLabel(cell.label(), textWidth, 1).getFirst(), ReportTheme.MUTED);
            y -= ReportTheme.LABEL + ReportTheme.SPACE_2;

            for (String line : PdfCanvas.wrap(cell.value(), PdfCanvas.Weight.BOLD, ReportTheme.BODY,
                    textWidth, MAX_VALUE_LINES)) {
                canvas.text(columnX, y - ReportTheme.BODY, line, PdfCanvas.Weight.BOLD,
                        ReportTheme.BODY, ReportTheme.INK);
                y -= ReportTheme.BODY * VALUE_LEADING;
            }

            if (cell.note() != null) {
                // Anchored to the band's own value block, not to this cell's, so the notes line up
                // even when one column's value wrapped and another's did not.
                float noteTop = top - PADDING - ReportTheme.LABEL - ReportTheme.SPACE_2
                        - valueLines * ReportTheme.BODY * VALUE_LEADING - ReportTheme.SPACE_1;
                canvas.text(columnX, noteTop - ReportTheme.NOTE,
                        PdfCanvas.fit(cell.note(), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                                textWidth),
                        PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
            }
        }
    }

    @Override
    public float spacingAfter() {
        return ReportTheme.SPACE_4;
    }

    /** The tallest value sets the band's height, or the columns would sit at different depths. */
    private int valueLines(float width) throws IOException {
        float textWidth = (width - 2 * PADDING) / cells.size() - ReportTheme.SPACE_3;
        int lines = 1;
        for (Cell cell : cells) {
            lines = Math.max(lines, PdfCanvas.wrap(cell.value(), PdfCanvas.Weight.BOLD,
                    ReportTheme.BODY, textWidth, MAX_VALUE_LINES).size());
        }
        return lines;
    }

    private boolean hasNote() {
        return cells.stream().anyMatch(cell -> cell.note() != null);
    }
}
