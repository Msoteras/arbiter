package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

public record ProseBlock(String heading, String body) implements Block {

    private static final float LEADING = 1.4f;

    @Override
    public float height(float width) throws IOException {
        return ReportTheme.SECTION + ReportTheme.SPACE_3
                + lines(width).size() * ReportTheme.BODY * LEADING;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        canvas.text(x, top - ReportTheme.SECTION, heading, PdfCanvas.Weight.BOLD,
                ReportTheme.SECTION, ReportTheme.INK);
        float y = top - ReportTheme.SECTION - ReportTheme.SPACE_3;
        for (String line : lines(width)) {
            canvas.text(x, y - ReportTheme.BODY, line, PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                    ReportTheme.INK_SOFT);
            y -= ReportTheme.BODY * LEADING;
        }
    }

    private List<String> lines(float width) throws IOException {
        return PdfCanvas.wrap(body, PdfCanvas.Weight.REGULAR, ReportTheme.BODY, width);
    }
}
