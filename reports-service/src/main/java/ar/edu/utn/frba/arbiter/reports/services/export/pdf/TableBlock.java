package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.List;

/**
 * The detail the aggregates were computed from, one case per row.
 *
 * <p>Cells stack a second, quieter line under the first so a portrait page can carry what a landscape
 * one needed a column for — a pair of dates, a decision under the recommendation it departed from.
 *
 * <p>The only block that splits across pages: everything else is placed whole, but a period's detail
 * is as long as it is.
 */
public record TableBlock(String heading, String caption, List<Column> columns, List<Row> rows,
                         String emptyMessage) implements Block {

    private static final float HEADER_PADDING = ReportTheme.SPACE_1;
    private static final float HEADER_LEADING = 1.35f;
    private static final int MAX_HEADER_LINES = 2;
    private static final float ROW_PADDING = 4.5f;
    private static final float LINE_LEADING = 1.25f;
    private static final float CELL_GAP = 5;
    private static final float RIGHT_GUTTER = ReportTheme.SPACE_3;
    private static final int MAX_LINES_PER_PART = 2;

    /** @param weight share of the table's width, relative to the other columns */
    public record Column(String header, float weight, boolean alignRight) {}

    /** @param highlight a tint across the whole row, or null; reserved for rows that need a look */
    public record Row(List<Cell> cells, Rgb highlight) {}

    /** @param secondary the quieter line under {@code primary}; null leaves the cell one line tall */
    public record Cell(Part primary, Part secondary) {

        public static Cell of(String text) {
            return new Cell(Part.plain(text), null);
        }

        public static Cell of(String primary, String secondary) {
            return new Cell(Part.plain(primary), Part.muted(secondary));
        }
    }

    public record Part(String text, Rgb color, boolean bold) {

        public static Part plain(String text) {
            return new Part(text, ReportTheme.INK, false);
        }

        public static Part muted(String text) {
            return new Part(text, ReportTheme.MUTED, false);
        }

        public static Part strong(String text, Rgb color) {
            return new Part(text, color, true);
        }
    }

    @Override
    public float height(float width) throws IOException {
        float total = headingHeight() + headerHeight(width);
        if (rows.isEmpty()) {
            total += ReportTheme.SPACE_3 + ReportTheme.BODY * 1.4f;
        }
        for (Row row : rows) {
            total += rowHeight(row, width);
        }
        return total;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float y = top;
        if (heading != null) {
            canvas.text(x, y - ReportTheme.SECTION, heading, PdfCanvas.Weight.BOLD,
                    ReportTheme.SECTION, ReportTheme.INK);
            if (caption != null) {
                float headingWidth =
                        PdfCanvas.width(heading, PdfCanvas.Weight.BOLD, ReportTheme.SECTION);
                canvas.text(x + headingWidth + ReportTheme.SPACE_2, y - ReportTheme.SECTION,
                        PdfCanvas.fit(caption, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                                width - headingWidth - ReportTheme.SPACE_2),
                        PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
            }
            y -= headingHeight();
        }

        float[] widths = columnWidths(width);
        float columnX = x;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            float lineY = y - HEADER_PADDING;
            for (String line : headerLines(column, widths[i])) {
                if (column.alignRight()) {
                    canvas.label(columnX + widths[i] - RIGHT_GUTTER - PdfCanvas.labelWidth(line),
                            lineY - ReportTheme.LABEL, line, ReportTheme.MUTED);
                } else {
                    canvas.label(columnX, lineY - ReportTheme.LABEL, line, ReportTheme.MUTED);
                }
                lineY -= ReportTheme.LABEL * HEADER_LEADING;
            }
            columnX += widths[i];
        }
        y -= headerHeight(width);
        canvas.line(x, y, x + width, y, ReportTheme.INK, ReportTheme.HAIRLINE * 2);

        if (rows.isEmpty()) {
            canvas.text(x, y - ReportTheme.SPACE_3 - ReportTheme.BODY, emptyMessage,
                    PdfCanvas.Weight.REGULAR, ReportTheme.BODY, ReportTheme.MUTED);
            y -= ReportTheme.SPACE_3 + ReportTheme.BODY * 1.4f;
        }

        for (Row row : rows) {
            float rowHeight = rowHeight(row, width);
            if (row.highlight() != null) {
                canvas.fillRect(x, y - rowHeight, width, rowHeight, row.highlight());
            }
            drawCells(canvas, row, x, y, widths);
            y -= rowHeight;
            canvas.line(x, y, x + width, y, ReportTheme.BORDER_SUBTLE, ReportTheme.HAIRLINE);
        }
    }

    @Override
    public Split split(float width, float available) throws IOException {
        float used = headingHeight() + headerHeight(width);
        int fits = 0;
        for (Row row : rows) {
            float rowHeight = rowHeight(row, width);
            if (used + rowHeight > available) {
                break;
            }
            used += rowHeight;
            fits++;
        }
        if (fits == 0 || fits == rows.size()) {
            return null;
        }
        // The continuation drops the heading: the page's running header already names the report.
        return new Split(
                new TableBlock(heading, caption, columns, rows.subList(0, fits), emptyMessage),
                new TableBlock(null, null, columns, rows.subList(fits, rows.size()), emptyMessage));
    }

    private void drawCells(PdfCanvas canvas, Row row, float x, float top, float[] widths)
            throws IOException {
        float columnX = x;
        for (int i = 0; i < row.cells().size(); i++) {
            Cell cell = row.cells().get(i);
            boolean alignRight = columns.get(i).alignRight();
            float y = top - ROW_PADDING;
            y = drawPart(canvas, cell.primary(), columnX, y, widths[i], ReportTheme.CELL, alignRight);
            if (cell.secondary() != null) {
                drawPart(canvas, cell.secondary(), columnX, y, widths[i], ReportTheme.CELL_SUB,
                        alignRight);
            }
            columnX += widths[i];
        }
    }

    private static float drawPart(PdfCanvas canvas, Part part, float x, float top, float width,
                                  float size, boolean alignRight) throws IOException {
        float y = top;
        for (String line : partLines(part, width, size, alignRight)) {
            if (alignRight) {
                canvas.textRight(x + width - RIGHT_GUTTER, y - size, line,
                        part.bold() ? PdfCanvas.Weight.BOLD : PdfCanvas.Weight.REGULAR, size,
                        part.color());
            } else {
                canvas.text(x, y - size, line,
                        part.bold() ? PdfCanvas.Weight.BOLD : PdfCanvas.Weight.REGULAR, size,
                        part.color());
            }
            y -= size * LINE_LEADING;
        }
        return y;
    }

    private float rowHeight(Row row, float width) throws IOException {
        float[] widths = columnWidths(width);
        float tallest = 0;
        for (int i = 0; i < row.cells().size(); i++) {
            Cell cell = row.cells().get(i);
            boolean alignRight = columns.get(i).alignRight();
            float height = partLines(cell.primary(), widths[i], ReportTheme.CELL, alignRight).size()
                    * ReportTheme.CELL * LINE_LEADING;
            if (cell.secondary() != null) {
                height += partLines(cell.secondary(), widths[i], ReportTheme.CELL_SUB, alignRight)
                        .size() * ReportTheme.CELL_SUB * LINE_LEADING;
            }
            tallest = Math.max(tallest, height);
        }
        return tallest + 2 * ROW_PADDING;
    }

    private static List<String> partLines(Part part, float width, float size, boolean alignRight)
            throws IOException {
        return PdfCanvas.wrap(part.text(), part.bold() ? PdfCanvas.Weight.BOLD
                        : PdfCanvas.Weight.REGULAR, size, width - gutter(alignRight),
                MAX_LINES_PER_PART);
    }

    /**
     * A left-aligned column is separated from the next one by that one's own left padding. A
     * right-aligned one ends where its cell ends, so it has to keep the gap itself or its values sit
     * against the column that follows.
     */
    private static float gutter(boolean alignRight) {
        return alignRight ? RIGHT_GUTTER : CELL_GAP;
    }

    private float[] columnWidths(float width) {
        float total = 0;
        for (Column column : columns) {
            total += column.weight();
        }
        float[] widths = new float[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = width * columns.get(i).weight() / total;
        }
        return widths;
    }

    private float headingHeight() {
        return heading == null ? 0 : ReportTheme.SECTION + ReportTheme.SPACE_3;
    }

    /**
     * Sized to the tallest column title rather than fixed: a portrait page makes some columns narrow,
     * and a title cut to fit ("Sistema · …") is the wrong title.
     */
    private float headerHeight(float width) throws IOException {
        float[] widths = columnWidths(width);
        int lines = 1;
        for (int i = 0; i < columns.size(); i++) {
            lines = Math.max(lines, headerLines(columns.get(i), widths[i]).size());
        }
        return 2 * HEADER_PADDING + lines * ReportTheme.LABEL * HEADER_LEADING;
    }

    private static List<String> headerLines(Column column, float width) throws IOException {
        return PdfCanvas.wrapLabel(column.header(), width - gutter(column.alignRight()),
                MAX_HEADER_LINES);
    }
}
