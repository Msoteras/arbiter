package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.List;

/**
 * A small cross-tab: what the system suggested against what the analyst decided.
 *
 * <p>The report's point in one shape — where the two agree, and the cells where they did not. It
 * carries its own legend because nothing else on the page says what a tinted cell is claiming.
 *
 * <p>The semaphore is per cell rather than per row: on a row of recommendations only one column can
 * be a departure, and toning the agreeing cell as well would read as if it were one too.
 */
public record MatrixBlock(String heading, List<String> columnLabels, List<Row> rows,
                          List<Legend> legend) implements Block {

    private static final float LABEL_FRACTION = 0.40f;
    private static final float CELL_WIDTH = 42;
    private static final float CELL_HEIGHT = 21;
    private static final float CELL_GAP = ReportTheme.SPACE_1;
    private static final float ROW_GAP = ReportTheme.SPACE_1;
    private static final float HEADER_LEADING = 1.3f;
    private static final int MAX_HEADER_LINES = 2;
    private static final float SWATCH = 7;
    private static final float LEGEND_LEADING = 1.35f;

    public record Row(String label, List<Cell> cells) {}

    /**
     * What a count in this cell means. Only a row that carried an actionable recommendation can be
     * agreed with or departed from; everywhere else the count is just a count, and toning it would
     * claim something the data does not say.
     */
    public enum Tone {AGREEMENT, DEPARTURE, NEUTRAL}

    public record Cell(long value, Tone tone) {}

    /** One line of "this colour means this". */
    public record Legend(String text, Tone tone) {}

    @Override
    public float height(float width) throws IOException {
        return ReportTheme.SECTION + ReportTheme.SPACE_3
                + headerLines() * ReportTheme.LABEL * HEADER_LEADING + ReportTheme.SPACE_2
                + rows.size() * (CELL_HEIGHT + ROW_GAP)
                + legendHeight();
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float y = top;
        canvas.text(x, y - ReportTheme.SECTION, heading, PdfCanvas.Weight.BOLD, ReportTheme.SECTION,
                ReportTheme.INK);
        y -= ReportTheme.SECTION + ReportTheme.SPACE_3;

        float labelWidth = width * LABEL_FRACTION;
        float cellsX = x + labelWidth;
        for (int column = 0; column < columnLabels.size(); column++) {
            float centre = cellsX + column * (CELL_WIDTH + CELL_GAP) + CELL_WIDTH / 2;
            float lineY = y;
            for (String line : labelLines(columnLabels.get(column))) {
                canvas.label(centre - PdfCanvas.labelWidth(line) / 2, lineY - ReportTheme.LABEL,
                        line, ReportTheme.MUTED);
                lineY -= ReportTheme.LABEL * HEADER_LEADING;
            }
        }
        y -= headerLines() * ReportTheme.LABEL * HEADER_LEADING + ReportTheme.SPACE_2;

        for (Row row : rows) {
            canvas.text(x, y - CELL_HEIGHT / 2 - ReportTheme.BODY / 2 + 1,
                    PdfCanvas.fit(row.label(), PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                            labelWidth - ReportTheme.SPACE_2),
                    PdfCanvas.Weight.REGULAR, ReportTheme.BODY, ReportTheme.INK_SOFT);

            for (int column = 0; column < row.cells().size(); column++) {
                Cell cell = row.cells().get(column);
                float cellX = cellsX + column * (CELL_WIDTH + CELL_GAP);
                canvas.panel(cellX, y - CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT,
                        ReportTheme.RADIUS_CARD, fill(cell), border(cell));
                canvas.textCentred(cellX + CELL_WIDTH / 2,
                        y - CELL_HEIGHT / 2 - ReportTheme.LEAD / 2 + 1, String.valueOf(cell.value()),
                        PdfCanvas.Weight.BOLD, ReportTheme.LEAD, ink(cell));
            }
            y -= CELL_HEIGHT + ROW_GAP;
        }

        if (legend.isEmpty()) {
            return;
        }
        y -= ReportTheme.SPACE_2;
        for (Legend entry : legend) {
            Cell swatch = new Cell(1, entry.tone());
            canvas.panel(x, y - SWATCH, SWATCH, SWATCH, 2, fill(swatch), border(swatch));
            canvas.text(x + SWATCH + ReportTheme.SPACE_2, y - SWATCH + 0.5f,
                    PdfCanvas.fit(entry.text(), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                            width - SWATCH - ReportTheme.SPACE_2),
                    PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
            y -= ReportTheme.NOTE * LEGEND_LEADING;
        }
    }

    private float legendHeight() {
        return legend.isEmpty() ? 0
                : ReportTheme.SPACE_2 + legend.size() * ReportTheme.NOTE * LEGEND_LEADING;
    }

    /** Column titles wrap rather than run into each other: the cells they head are narrow. */
    private static List<String> labelLines(String label) throws IOException {
        return PdfCanvas.wrapLabel(label, CELL_WIDTH + CELL_GAP, MAX_HEADER_LINES);
    }

    private int headerLines() throws IOException {
        int lines = 1;
        for (String label : columnLabels) {
            lines = Math.max(lines, labelLines(label).size());
        }
        return lines;
    }

    private static Rgb fill(Cell cell) {
        if (cell.value() == 0) {
            return ReportTheme.SURFACE;
        }
        return switch (cell.tone()) {
            case DEPARTURE -> ReportTheme.DANGER_SOFT;
            case AGREEMENT -> ReportTheme.ACCENT_SOFT;
            case NEUTRAL -> ReportTheme.SURFACE_SUNKEN;
        };
    }

    private static Rgb border(Cell cell) {
        if (cell.value() == 0) {
            return ReportTheme.BORDER_SUBTLE;
        }
        return switch (cell.tone()) {
            case DEPARTURE -> ReportTheme.DANGER_SOFT_BORDER;
            case AGREEMENT -> ReportTheme.ACCENT_SOFT_BORDER;
            case NEUTRAL -> ReportTheme.BORDER;
        };
    }

    private static Rgb ink(Cell cell) {
        if (cell.value() == 0) {
            return ReportTheme.MUTED_SOFT;
        }
        return cell.tone() == Tone.DEPARTURE ? ReportTheme.STATUS_DANGER : ReportTheme.INK;
    }
}
