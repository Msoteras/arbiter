package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.exceptions.ReportGenerationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a report as a landscape A4 table: heading, header row repeated on every page, and page
 * numbers at the foot. Shared by every {@code Pdf*Exporter} so all reports look the same; only the
 * columns and cells vary, which is all the {@link Spec} carries.
 */
final class PdfReportWriter {

    private static final PDRectangle PAGE =
            new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 36;
    private static final float FOOTER_BASELINE = 20;
    private static final float TITLE_SIZE = 14;
    private static final float META_SIZE = 9;
    private static final float CELL_SIZE = 7.5f;
    private static final float ROW_HEIGHT = 14;
    private static final float CELL_PADDING = 3;
    private static final float TEXT_BASELINE_OFFSET = 4.5f;
    /** What a second line of the same cell adds to the row. */
    private static final float LINE_HEIGHT = CELL_SIZE + 2;
    /**
     * Two lines: more stops reading as a table, and one would ellipsize the fraud report's signal list,
     * hiding the coinciding signals that report exists to show.
     */
    private static final int MAX_CELL_LINES = 2;

    private static final float INK = 0.1f;
    private static final float MUTED = 0.4f;
    private static final float RULE = 0.85f;
    private static final float HEAD_FILL = 0.94f;

    private static final String ELLIPSIS = "…";

    static final float CONTENT_WIDTH = PAGE.getWidth() - 2 * MARGIN;

    /** Prefix of the one heading line rendered in bold. */
    static final String TOTALS_LABEL = "Total: ";

    private PdfReportWriter() {
    }

    /**
     * @param headingLines the filter line and the summary, in order
     * @param emptyMessage shown when there are no rows, so an empty report still says which one it is
     */
    record Spec(
            String title,
            List<String> headingLines,
            String[] header,
            float[] widths,
            List<String[]> rows,
            String emptyMessage,
            Instant generatedAt,
            ZoneId zone
    ) {
        Spec {
            if (header.length != widths.length) {
                throw new IllegalArgumentException("header and widths must have one entry per column");
            }
            if (sum(widths) > CONTENT_WIDTH) {
                throw new IllegalArgumentException(
                        "columns add up to %.0fpt, wider than the %.0fpt a landscape A4 leaves"
                                .formatted(sum(widths), CONTENT_WIDTH));
            }
        }

        float tableWidth() {
            return sum(widths);
        }
    }

    static byte[] render(Spec spec) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPageContentStream content = newPage(document);
            float y = drawHeading(content, regular, bold, spec, PAGE.getHeight() - MARGIN);
            y = drawHeaderRow(content, bold, spec, y);
            if (spec.rows().isEmpty()) {
                text(content, regular, META_SIZE, MUTED, MARGIN + CELL_PADDING, y - ROW_HEIGHT,
                        spec.emptyMessage());
            }
            for (String[] row : spec.rows()) {
                // Measured before deciding the page break: a wrapped row is taller than ROW_HEIGHT.
                List<List<String>> lines = cellLines(row, regular, spec);
                float height = rowHeight(lines);
                if (y - height < MARGIN) {
                    content.close();
                    content = newPage(document);
                    y = drawHeaderRow(content, bold, spec, PAGE.getHeight() - MARGIN);
                }
                y = drawRow(content, regular, spec, lines, y);
            }
            content.close();

            drawFooters(document, regular, spec);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("Could not write the " + spec.title() + " PDF", e);
        }
    }

    private static PDPageContentStream newPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PAGE);
        document.addPage(page);
        return new PDPageContentStream(document, page);
    }

    /** @return the y where the table starts */
    private static float drawHeading(PDPageContentStream content, PDFont regular, PDFont bold,
                                     Spec spec, float top) throws IOException {
        float y = top - TITLE_SIZE;
        text(content, bold, TITLE_SIZE, INK, MARGIN, y, spec.title());

        // Wrapped, not cut: a filter or total hidden behind an ellipsis can't be recovered from a file.
        for (String line : spec.headingLines()) {
            PDFont font = line.startsWith(TOTALS_LABEL) ? bold : regular;
            for (String part : wrap(line, font, META_SIZE, spec.tableWidth())) {
                y -= META_SIZE + 4;
                text(content, font, META_SIZE, INK, MARGIN, y, part);
            }
        }

        y -= META_SIZE + 4;
        text(content, regular, META_SIZE, MUTED, MARGIN, y, "Generado el "
                + ReportLabels.DATE_TIME.withZone(spec.zone()).format(spec.generatedAt()));
        return y - 14;
    }

    /** One line per column: a column title that had to wrap would be the wrong title. */
    private static float drawHeaderRow(PDPageContentStream content, PDFont bold, Spec spec, float top)
            throws IOException {
        float bottom = top - ROW_HEIGHT;
        content.setNonStrokingColor(HEAD_FILL);
        content.addRect(MARGIN, bottom, spec.tableWidth(), ROW_HEIGHT);
        content.fill();
        List<List<String>> lines = new ArrayList<>(spec.header().length);
        for (int i = 0; i < spec.header().length; i++) {
            lines.add(List.of(fit(spec.header()[i], bold, CELL_SIZE, textWidth(spec, i))));
        }
        drawCells(content, bold, spec, lines, top);
        rule(content, spec, bottom);
        return bottom;
    }

    private static float drawRow(PDPageContentStream content, PDFont regular, Spec spec,
                                 List<List<String>> lines, float top) throws IOException {
        float bottom = top - rowHeight(lines);
        drawCells(content, regular, spec, lines, top);
        rule(content, spec, bottom);
        return bottom;
    }

    /** Wraps every cell to its column, so the row's height is known before anything is drawn. */
    private static List<List<String>> cellLines(String[] cells, PDFont font, Spec spec)
            throws IOException {
        List<List<String>> lines = new ArrayList<>(cells.length);
        for (int i = 0; i < cells.length; i++) {
            lines.add(wrap(cells[i], font, CELL_SIZE, textWidth(spec, i), MAX_CELL_LINES));
        }
        return lines;
    }

    private static float rowHeight(List<List<String>> lines) {
        int tallest = lines.stream().mapToInt(List::size).max().orElse(1);
        return ROW_HEIGHT + (tallest - 1) * LINE_HEIGHT;
    }

    private static void drawCells(PDPageContentStream content, PDFont font, Spec spec,
                                  List<List<String>> lines, float top) throws IOException {
        float x = MARGIN;
        for (int i = 0; i < lines.size(); i++) {
            float baseline = top - ROW_HEIGHT + TEXT_BASELINE_OFFSET;
            for (String line : lines.get(i)) {
                text(content, font, CELL_SIZE, INK, x + CELL_PADDING, baseline, line);
                baseline -= LINE_HEIGHT;
            }
            x += spec.widths()[i];
        }
    }

    private static float textWidth(Spec spec, int column) {
        return spec.widths()[column] - 2 * CELL_PADDING;
    }

    private static void rule(PDPageContentStream content, Spec spec, float y) throws IOException {
        content.setStrokingColor(RULE);
        content.setLineWidth(0.5f);
        content.moveTo(MARGIN, y);
        content.lineTo(MARGIN + spec.tableWidth(), y);
        content.stroke();
    }

    /** Page numbers go in last: "de m" isn't known until every row has been laid out. */
    private static void drawFooters(PDDocument document, PDFont regular, Spec spec) throws IOException {
        int total = document.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream content = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                text(content, regular, CELL_SIZE, MUTED, MARGIN, FOOTER_BASELINE,
                        "Arbiter · " + spec.title());
                String pageLabel = "Página %d de %d".formatted(i + 1, total);
                float width = width(pageLabel, regular, CELL_SIZE);
                text(content, regular, CELL_SIZE, MUTED, MARGIN + spec.tableWidth() - width,
                        FOOTER_BASELINE, pageLabel);
            }
        }
    }

    private static void text(PDPageContentStream content, PDFont font, float size, float gray,
                             float x, float y, String value) throws IOException {
        content.beginText();
        content.setNonStrokingColor(gray);
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(printable(value, font));
        content.endText();
    }

    private static String fit(String value, PDFont font, float size, float maxWidth) throws IOException {
        String text = printable(value, font);
        if (width(text, font, size) <= maxWidth) {
            return text;
        }
        while (!text.isEmpty() && width(text + ELLIPSIS, font, size) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ELLIPSIS;
    }

    /** Overflow beyond {@code maxLines} is ellipsized into the last line, so a cut value shows it was cut. */
    private static List<String> wrap(String value, PDFont font, float size, float maxWidth,
                                     int maxLines) throws IOException {
        List<String> lines = wrap(value, font, size, maxWidth);
        if (lines.size() <= maxLines) {
            return lines;
        }
        List<String> capped = new ArrayList<>(lines.subList(0, maxLines - 1));
        capped.add(fit(String.join(" ", lines.subList(maxLines - 1, lines.size())), font, size, maxWidth));
        return capped;
    }

    private static List<String> wrap(String value, PDFont font, float size, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : printable(value, font).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || width(candidate, font, size) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(fit(line.toString(), font, size, maxWidth));
                line.setLength(0);
                line.append(word);
            }
        }
        lines.add(fit(line.toString(), font, size, maxWidth));
        return lines;
    }

    private static float width(String text, PDFont font, float size) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /**
     * The standard 14 fonts only encode WinAnsi; one unsupported character would make {@code showText}
     * throw and lose the whole file.
     */
    private static String printable(String value, PDFont font) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String glyph = Character.isWhitespace(codePoint) ? " " : Character.toString(codePoint);
            out.append(canEncode(font, glyph) ? glyph : "?");
        });
        return out.toString();
    }

    private static boolean canEncode(PDFont font, String glyph) {
        try {
            font.encode(glyph);
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private static float sum(float[] values) {
        float total = 0;
        for (float value : values) {
            total += value;
        }
        return total;
    }
}
