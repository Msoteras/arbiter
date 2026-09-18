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
 * Lays out a report as a landscape A4 table: heading, header row repeated on every page, and
 * "Página n de m" at the foot. Built with PDFBox's own drawing primitives rather than an
 * HTML-to-PDF engine — it's one table, and PDFBox is already the project's PDF library.
 *
 * <p>Shared by every {@code Pdf*Exporter}: the layout is the document's identity, so two reports
 * from the same platform drifting into two different-looking tables is a defect, not a detail. What
 * changes per report is the columns and what goes in them, which is all the spec carries.
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

    private static final float INK = 0.1f;
    private static final float MUTED = 0.4f;
    private static final float RULE = 0.85f;
    private static final float HEAD_FILL = 0.94f;

    private static final String ELLIPSIS = "…";

    /** The width a landscape A4 leaves between margins; every report's columns have to fit in it. */
    static final float CONTENT_WIDTH = PAGE.getWidth() - 2 * MARGIN;

    /** Prefix that marks the one heading line rendered in bold — the totals of the period. */
    static final String TOTALS_LABEL = "Total: ";

    private PdfReportWriter() {
    }

    /**
     * @param headingLines the filter line and the summary, in order. A line starting with
     *                     {@link #TOTALS_LABEL} is drawn in bold
     * @param emptyMessage what the page says when there are no rows — an empty report still has to
     *                     be distinguishable from any other empty report
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
                if (y - ROW_HEIGHT < MARGIN) {
                    content.close();
                    content = newPage(document);
                    y = drawHeaderRow(content, bold, spec, PAGE.getHeight() - MARGIN);
                }
                y = drawRow(content, regular, spec, row, y);
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

        // Wrapped, not cut: a heading line is a filter or a total, and an auditor reading the file
        // has no screen to go and find the half the ellipsis hid.
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

    private static float drawHeaderRow(PDPageContentStream content, PDFont bold, Spec spec, float top)
            throws IOException {
        float bottom = top - ROW_HEIGHT;
        content.setNonStrokingColor(HEAD_FILL);
        content.addRect(MARGIN, bottom, spec.tableWidth(), ROW_HEIGHT);
        content.fill();
        drawCells(content, bold, spec, spec.header(), bottom);
        rule(content, spec, bottom);
        return bottom;
    }

    private static float drawRow(PDPageContentStream content, PDFont regular, Spec spec, String[] cells,
                                 float top) throws IOException {
        float bottom = top - ROW_HEIGHT;
        drawCells(content, regular, spec, cells, bottom);
        rule(content, spec, bottom);
        return bottom;
    }

    private static void drawCells(PDPageContentStream content, PDFont font, Spec spec, String[] cells,
                                  float bottom) throws IOException {
        float x = MARGIN;
        for (int i = 0; i < cells.length; i++) {
            String value = fit(cells[i], font, CELL_SIZE, spec.widths()[i] - 2 * CELL_PADDING);
            text(content, font, CELL_SIZE, INK, x + CELL_PADDING, bottom + TEXT_BASELINE_OFFSET, value);
            x += spec.widths()[i];
        }
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

    /** Cuts the text to the column, with an ellipsis, instead of letting it spill into the next one. */
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

    /** Breaks the text at spaces so every line fits; a single word too long for it is cut. */
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
     * The standard 14 fonts only encode WinAnsi: enough for Spanish, but one name with a character
     * outside it would make {@code showText} throw and lose the whole file over a single glyph.
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
