package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.exceptions.ReportGenerationException;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Landscape A4 table, one row per case, header repeated on every page and "Página n de m" at the
 * foot. Built with PDFBox's own drawing primitives rather than an HTML-to-PDF engine: it's one
 * table, and PDFBox is already the project's PDF library.
 */
@Component
@RequiredArgsConstructor
public class PdfResolutionReportExporter implements ResolutionReportExporter {

    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
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

    private static final String TITLE = "Reporte de resolución de siniestros";
    private static final String ELLIPSIS = "…";

    private static final String[] HEADER = {
            "Nº", "Asegurado", "DNI", "Ramo · Hecho generador", "Denuncia", "Resolución", "Tiempo",
            "Clasificación", "Decisión", "Estado", "Analista"};
    // Sums to 766pt, just inside the 770pt a landscape A4 leaves between margins.
    private static final float[] WIDTHS = {36, 110, 58, 120, 56, 56, 50, 88, 50, 56, 86};
    private static final float TABLE_WIDTH = sum(WIDTHS);

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] export(ResolutionReport report) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            ZoneId zone = clock.getZone();

            PDPageContentStream content = newPage(document);
            float y = drawHeading(content, regular, bold, report, zone, PAGE.getHeight() - MARGIN);
            y = drawHeaderRow(content, bold, y);
            if (report.rows().isEmpty()) {
                text(content, regular, META_SIZE, MUTED, MARGIN + CELL_PADDING, y - ROW_HEIGHT,
                        "No hay siniestros resueltos en el período.");
            }
            for (ResolutionReportRow row : report.rows()) {
                if (y - ROW_HEIGHT < MARGIN) {
                    content.close();
                    content = newPage(document);
                    y = drawHeaderRow(content, bold, PAGE.getHeight() - MARGIN);
                }
                y = drawRow(content, regular, cells(row, zone), y);
            }
            content.close();

            drawFooters(document, regular);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("Could not write the resolution report PDF", e);
        }
    }

    private static PDPageContentStream newPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PAGE);
        document.addPage(page);
        return new PDPageContentStream(document, page);
    }

    /** @return the y where the table starts */
    private float drawHeading(PDPageContentStream content, PDFont regular, PDFont bold,
                              ResolutionReport report, ZoneId zone, float top) throws IOException {
        float y = top - TITLE_SIZE;
        text(content, bold, TITLE_SIZE, INK, MARGIN, y, TITLE);

        int count = report.rows().size();
        String period = "Período: %s al %s · Tipo de siniestro: %s · %d %s".formatted(
                ReportLabels.DATE.format(report.from()),
                ReportLabels.DATE.format(report.to()),
                ReportLabels.claimCauseFilter(report.claimCause()),
                count,
                count == 1 ? "siniestro resuelto" : "siniestros resueltos");
        y -= META_SIZE + 8;
        text(content, regular, META_SIZE, INK, MARGIN, y, fit(period, regular, META_SIZE, TABLE_WIDTH));

        y -= META_SIZE + 4;
        text(content, regular, META_SIZE, MUTED, MARGIN, y,
                "Generado el " + ReportLabels.DATE_TIME.withZone(zone).format(report.generatedAt()));
        return y - 14;
    }

    private static float drawHeaderRow(PDPageContentStream content, PDFont bold, float top) throws IOException {
        float bottom = top - ROW_HEIGHT;
        content.setNonStrokingColor(HEAD_FILL);
        content.addRect(MARGIN, bottom, TABLE_WIDTH, ROW_HEIGHT);
        content.fill();
        drawCells(content, bold, HEADER, bottom);
        rule(content, bottom);
        return bottom;
    }

    private static float drawRow(PDPageContentStream content, PDFont regular, String[] cells, float top)
            throws IOException {
        float bottom = top - ROW_HEIGHT;
        drawCells(content, regular, cells, bottom);
        rule(content, bottom);
        return bottom;
    }

    private static void drawCells(PDPageContentStream content, PDFont font, String[] cells, float bottom)
            throws IOException {
        float x = MARGIN;
        for (int i = 0; i < cells.length; i++) {
            String value = fit(cells[i], font, CELL_SIZE, WIDTHS[i] - 2 * CELL_PADDING);
            text(content, font, CELL_SIZE, INK, x + CELL_PADDING, bottom + TEXT_BASELINE_OFFSET, value);
            x += WIDTHS[i];
        }
    }

    private static void rule(PDPageContentStream content, float y) throws IOException {
        content.setStrokingColor(RULE);
        content.setLineWidth(0.5f);
        content.moveTo(MARGIN, y);
        content.lineTo(MARGIN + TABLE_WIDTH, y);
        content.stroke();
    }

    /** Page numbers go in last: "de m" isn't known until every row has been laid out. */
    private static void drawFooters(PDDocument document, PDFont regular) throws IOException {
        int total = document.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream content = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                text(content, regular, CELL_SIZE, MUTED, MARGIN, FOOTER_BASELINE, "Arbiter · " + TITLE);
                String pageLabel = "Página %d de %d".formatted(i + 1, total);
                float width = width(pageLabel, regular, CELL_SIZE);
                text(content, regular, CELL_SIZE, MUTED, MARGIN + TABLE_WIDTH - width, FOOTER_BASELINE, pageLabel);
            }
        }
    }

    private static String[] cells(ResolutionReportRow row, ZoneId zone) {
        return new String[]{
                String.valueOf(row.caseId()),
                row.insuredName(),
                row.insuredDni(),
                row.branch() + " · " + row.claimCause(),
                ReportLabels.DATE.withZone(zone).format(row.reportedAt()),
                ReportLabels.DATE.withZone(zone).format(row.resolvedAt()),
                ReportLabels.duration(row.totalMinutes()),
                ReportLabels.classification(row.classification()),
                ReportLabels.decision(row.analystDecision()),
                ReportLabels.status(row.finalStatus()),
                row.analystName() == null ? "—" : row.analystName()};
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

    // Keeps HEADER and WIDTHS from drifting apart when a column is added to one and not the other.
    static {
        if (HEADER.length != WIDTHS.length) {
            throw new IllegalStateException("HEADER and WIDTHS must have one entry per column");
        }
    }
}
