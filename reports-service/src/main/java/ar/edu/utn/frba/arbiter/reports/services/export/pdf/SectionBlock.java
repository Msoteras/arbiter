package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;

public record SectionBlock(String heading, String caption) implements Block {

    public static SectionBlock of(String heading) {
        return new SectionBlock(heading, null);
    }

    @Override
    public float height(float width) {
        return ReportTheme.SECTION + ReportTheme.SPACE_2;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float baseline = top - ReportTheme.SECTION;
        canvas.text(x, baseline, heading, PdfCanvas.Weight.BOLD, ReportTheme.SECTION,
                ReportTheme.INK);
        if (caption != null) {
            float headingWidth = PdfCanvas.width(heading, PdfCanvas.Weight.BOLD, ReportTheme.SECTION);
            canvas.text(x + headingWidth + ReportTheme.SPACE_2, baseline,
                    PdfCanvas.fit(caption, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                            width - headingWidth - ReportTheme.SPACE_2),
                    PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
        }
        canvas.line(x, top - ReportTheme.SECTION - ReportTheme.SPACE_2, x + width,
                top - ReportTheme.SECTION - ReportTheme.SPACE_2, ReportTheme.INK,
                ReportTheme.HAIRLINE * 2);
    }

    @Override
    public float spacingAfter() {
        return ReportTheme.SPACE_3;
    }
}
