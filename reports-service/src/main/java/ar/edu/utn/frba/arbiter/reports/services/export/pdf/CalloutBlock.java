package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.List;

/**
 * A tinted panel stating what the report does and does not cover.
 *
 * <p>Every report carries one. A figure read out of a document that never said what it counted is
 * the way these end up quoted for something they do not measure.
 */
public record CalloutBlock(String label, String body, Rgb fill, Rgb border, Rgb labelColor)
        implements Block {

    private static final float PADDING = 10;
    private static final float LEADING = 1.35f;

    /** The neutral, brand-tinted callout every report opens with. */
    public static CalloutBlock scope(String body) {
        return new CalloutBlock("Alcance de este informe", body, ReportTheme.ACCENT_SOFT,
                ReportTheme.ACCENT_SOFT_BORDER, ReportTheme.ACCENT_STRONG);
    }

    @Override
    public float height(float width) throws IOException {
        return 2 * PADDING + ReportTheme.LABEL + ReportTheme.SPACE_2
                + lines(width).size() * ReportTheme.BODY * LEADING;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float height = height(width);
        canvas.panel(x, top - height, width, height, ReportTheme.RADIUS_CARD, fill, border);
        canvas.label(x + PADDING, top - PADDING - ReportTheme.LABEL, label, labelColor);

        float baseline = top - PADDING - ReportTheme.LABEL - ReportTheme.SPACE_2 - ReportTheme.BODY;
        for (String line : lines(width)) {
            canvas.text(x + PADDING, baseline, line, PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                    ReportTheme.INK_SOFT);
            baseline -= ReportTheme.BODY * LEADING;
        }
    }

    private List<String> lines(float width) throws IOException {
        return PdfCanvas.wrap(body, PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                width - 2 * PADDING);
    }
}
