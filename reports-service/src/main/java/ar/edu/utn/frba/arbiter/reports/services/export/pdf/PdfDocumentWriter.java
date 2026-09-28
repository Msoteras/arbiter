package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.exceptions.ReportGenerationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public final class PdfDocumentWriter {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 42;
    private static final float CONTENT_WIDTH = PAGE.getWidth() - 2 * MARGIN;

    private static final float TOP_RULE = 3;

    private static final float BRAND_ROW = 18;
    private static final float RUNNING_ROW = 11;
    private static final float HEADER_GAP = 9;
    private static final float MARK_SIZE = 15;
    private static final float MONOGRAM_SIZE = 8.5f;

    private static final float FOOTER_RULE_Y = 64;
    private static final float CONTENT_BOTTOM = FOOTER_RULE_Y + 16;

    private static final String WORDMARK = "Arbiter";
    private static final String GENERATED_WITH = "Generado con";

    private PdfDocumentWriter() {
    }

    public static byte[] render(ReportDocument document) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Page page = newPage(pdf, document, true);
            for (Block block : document.blocks()) {
                page = place(pdf, document, page, block);
            }
            page.content.close();

            drawFooters(pdf, document);
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("Could not write the " + document.title() + " PDF", e);
        }
    }

    private static Page place(PDDocument pdf, ReportDocument document, Page page, Block block)
            throws IOException {
        Block pending = block;
        while (true) {
            float available = page.y - CONTENT_BOTTOM;
            if (pending.height(CONTENT_WIDTH) <= available) {
                pending.draw(page.canvas, MARGIN, page.y, CONTENT_WIDTH);
                page.y -= pending.height(CONTENT_WIDTH) + pending.spacingAfter();
                return page;
            }
            Block.Split split = pending.split(CONTENT_WIDTH, available);
            if (split != null) {
                split.head().draw(page.canvas, MARGIN, page.y, CONTENT_WIDTH);
                page = nextPage(pdf, document, page);
                pending = split.tail();
                continue;
            }
            if (page.isEmpty()) {
                // Taller than a page and indivisible: drawing it overflows, another page never ends.
                pending.draw(page.canvas, MARGIN, page.y, CONTENT_WIDTH);
                page.y -= pending.height(CONTENT_WIDTH) + pending.spacingAfter();
                return page;
            }
            page = nextPage(pdf, document, page);
        }
    }

    private static Page nextPage(PDDocument pdf, ReportDocument document, Page current)
            throws IOException {
        current.content.close();
        return newPage(pdf, document, false);
    }

    private static Page newPage(PDDocument pdf, ReportDocument document, boolean first)
            throws IOException {
        PDPage pdPage = new PDPage(PAGE);
        pdf.addPage(pdPage);
        PDPageContentStream content = new PDPageContentStream(pdf, pdPage);
        PdfCanvas canvas = new PdfCanvas(content);

        canvas.fillRect(0, PAGE.getHeight() - TOP_RULE, PAGE.getWidth(), TOP_RULE, ReportTheme.INK);
        float top = first ? drawBrandHeader(canvas, document) : drawRunningHeader(canvas, document);
        return new Page(content, canvas, top);
    }

    private static float drawBrandHeader(PdfCanvas canvas, ReportDocument document)
            throws IOException {
        ReportBranding branding = document.branding();
        float top = PAGE.getHeight() - MARGIN;
        float boxBottom = top - BRAND_ROW;
        float baseline = boxBottom + (BRAND_ROW - ReportTheme.LEAD) / 2 + 1.5f;
        float right = MARGIN + CONTENT_WIDTH;

        drawMonogram(canvas, branding, MARGIN, boxBottom, BRAND_ROW, MONOGRAM_SIZE);

        float wordmark = PdfCanvas.width(WORDMARK, PdfCanvas.Weight.BOLD, ReportTheme.LEAD);
        float label = PdfCanvas.width(GENERATED_WITH, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE);
        float markLeft = right - wordmark - 3 - MARK_SIZE;
        float nameWidth = markLeft - label - ReportTheme.SPACE_3 - MARGIN - BRAND_ROW
                - ReportTheme.SPACE_2;
        canvas.text(MARGIN + BRAND_ROW + ReportTheme.SPACE_2, baseline,
                PdfCanvas.fit(branding.insurerLegalName(), PdfCanvas.Weight.BOLD, ReportTheme.LEAD,
                        nameWidth),
                PdfCanvas.Weight.BOLD, ReportTheme.LEAD, ReportTheme.INK);

        canvas.text(markLeft - ReportTheme.SPACE_2 - label, baseline, GENERATED_WITH,
                PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
        ArbiterMark.draw(canvas.stream(), markLeft, boxBottom + (BRAND_ROW - MARK_SIZE) / 2,
                MARK_SIZE, ReportTheme.INK);
        canvas.text(right - wordmark, baseline, WORDMARK, PdfCanvas.Weight.BOLD, ReportTheme.LEAD,
                ReportTheme.INK);

        float ruleY = boxBottom - HEADER_GAP;
        canvas.line(MARGIN, ruleY, right, ruleY, ReportTheme.BORDER, ReportTheme.HAIRLINE);
        return ruleY - ReportTheme.SPACE_5;
    }

    private static float drawRunningHeader(PdfCanvas canvas, ReportDocument document)
            throws IOException {
        float top = PAGE.getHeight() - MARGIN;
        float boxBottom = top - RUNNING_ROW;
        float baseline = boxBottom + (RUNNING_ROW - ReportTheme.NOTE) / 2 + 1;
        float right = MARGIN + CONTENT_WIDTH;

        drawMonogram(canvas, document.branding(), MARGIN, boxBottom, RUNNING_ROW, ReportTheme.NOTE - 1);

        float x = MARGIN + RUNNING_ROW + ReportTheme.SPACE_2;
        float codeWidth = PdfCanvas.width(document.code(), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE);
        float periodWidth =
                PdfCanvas.width(document.periodLabel(), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE);
        float titleWidth = right - codeWidth - periodWidth - x - 2 * ReportTheme.SPACE_4;

        canvas.text(x, baseline,
                PdfCanvas.fit(document.branding().insurerName() + " · " + document.title(),
                        PdfCanvas.Weight.BOLD, ReportTheme.NOTE, titleWidth),
                PdfCanvas.Weight.BOLD, ReportTheme.NOTE, ReportTheme.INK);
        canvas.textRight(right - codeWidth - ReportTheme.SPACE_4, baseline, document.periodLabel(),
                PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
        canvas.textRight(right, baseline, document.code(), PdfCanvas.Weight.REGULAR,
                ReportTheme.NOTE, ReportTheme.MUTED);

        float ruleY = boxBottom - HEADER_GAP;
        canvas.line(MARGIN, ruleY, right, ruleY, ReportTheme.BORDER, ReportTheme.HAIRLINE);
        return ruleY - ReportTheme.SPACE_5;
    }

    private static void drawMonogram(PdfCanvas canvas, ReportBranding branding, float x, float y,
                                     float box, float size) throws IOException {
        canvas.panel(x, y, box, box, 3, ReportTheme.INK, null);
        String monogram = branding.monogram();
        canvas.text(x + (box - PdfCanvas.width(monogram, PdfCanvas.Weight.BOLD, size)) / 2,
                y + (box - size) / 2 + 1.5f, monogram, PdfCanvas.Weight.BOLD, size,
                ReportTheme.ON_INK);
    }

    /** Last: "de m" is not known until every block has been laid out. */
    private static void drawFooters(PDDocument pdf, ReportDocument document) throws IOException {
        int total = pdf.getNumberOfPages();
        List<String> note = PdfCanvas.wrap(document.confidentialityNote(),
                PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, CONTENT_WIDTH * 0.62f, 2);
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream content = new PDPageContentStream(
                    pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                PdfCanvas canvas = new PdfCanvas(content);
                canvas.line(MARGIN, FOOTER_RULE_Y, MARGIN + CONTENT_WIDTH, FOOTER_RULE_Y,
                        ReportTheme.BORDER, ReportTheme.HAIRLINE);

                float y = FOOTER_RULE_Y - ReportTheme.SPACE_2;
                for (String line : note) {
                    canvas.text(MARGIN, y - ReportTheme.NOTE, line, PdfCanvas.Weight.REGULAR,
                            ReportTheme.NOTE, ReportTheme.MUTED);
                    y -= ReportTheme.NOTE * 1.3f;
                }
                canvas.textRight(MARGIN + CONTENT_WIDTH,
                        FOOTER_RULE_Y - ReportTheme.SPACE_2 - ReportTheme.NOTE,
                        "Generado con Arbiter · Página %d de %d".formatted(i + 1, total),
                        PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
            }
        }
    }

    private static final class Page {

        private final PDPageContentStream content;
        private final PdfCanvas canvas;
        private final float top;
        private float y;

        private Page(PDPageContentStream content, PdfCanvas canvas, float top) {
            this.content = content;
            this.canvas = canvas;
            this.top = top;
            this.y = top;
        }

        private boolean isEmpty() {
            return y == top;
        }
    }
}
