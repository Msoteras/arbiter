package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

/** One quiet line under something louder, such as the comparison against the previous period. */
public record CaptionBlock(String text) implements Block {

    private static final float LEADING = 1.3f;

    @Override
    public float height(float width) throws IOException {
        return lines(width).size() * ReportTheme.NOTE * LEADING;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float y = top;
        for (String line : lines(width)) {
            canvas.text(x, y - ReportTheme.NOTE, line, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                    ReportTheme.MUTED);
            y -= ReportTheme.NOTE * LEADING;
        }
    }

    private List<String> lines(float width) throws IOException {
        return PdfCanvas.wrap(text, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, width);
    }
}
