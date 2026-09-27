package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

/**
 * The report's name, the period it covers and the code it is filed under.
 *
 * <p>The code sits in a box of its own on the opposite margin because it is what somebody quotes when
 * they ask about this particular run — it has to be findable without reading the title.
 */
public record TitleBlock(String title, String periodLabel, String code) implements Block {

    private static final float CODE_WIDTH = 132;
    private static final float CODE_PADDING = 8;
    private static final float TITLE_LEADING = 1.16f;

    @Override
    public float height(float width) throws IOException {
        return Math.max(textHeight(width), codeHeight());
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float boxHeight = codeHeight();
        float boxX = x + width - CODE_WIDTH;
        canvas.panel(boxX, top - boxHeight, CODE_WIDTH, boxHeight, ReportTheme.RADIUS_CARD,
                ReportTheme.SURFACE, ReportTheme.BORDER);
        float labelWidth = PdfCanvas.labelWidth("Código");
        canvas.label(boxX + CODE_WIDTH - CODE_PADDING - labelWidth,
                top - CODE_PADDING - ReportTheme.LABEL, "Código", ReportTheme.MUTED);
        float codeBaseline = top - CODE_PADDING - ReportTheme.LABEL - ReportTheme.SPACE_1
                - ReportTheme.BODY;
        for (String line : codeLines()) {
            canvas.textRight(boxX + CODE_WIDTH - CODE_PADDING, codeBaseline, line,
                    PdfCanvas.Weight.BOLD, ReportTheme.BODY, ReportTheme.INK);
            codeBaseline -= ReportTheme.BODY * 1.3f;
        }

        float y = top;
        for (String line : titleLines(width)) {
            canvas.text(x, y - ReportTheme.TITLE, line, PdfCanvas.Weight.BOLD, ReportTheme.TITLE,
                    ReportTheme.INK);
            y -= ReportTheme.TITLE * TITLE_LEADING;
        }
        y -= ReportTheme.SPACE_2;
        canvas.text(x, y - ReportTheme.LEAD, periodLabel, PdfCanvas.Weight.REGULAR,
                ReportTheme.LEAD, ReportTheme.MUTED);
    }

    @Override
    public float spacingAfter() {
        return ReportTheme.SPACE_4;
    }

    private List<String> titleLines(float width) throws IOException {
        return PdfCanvas.wrap(title, PdfCanvas.Weight.BOLD, ReportTheme.TITLE,
                width - CODE_WIDTH - ReportTheme.SPACE_5, 2);
    }

    private List<String> codeLines() throws IOException {
        return PdfCanvas.wrap(code, PdfCanvas.Weight.BOLD, ReportTheme.BODY,
                CODE_WIDTH - 2 * CODE_PADDING, 2);
    }

    private float textHeight(float width) throws IOException {
        return titleLines(width).size() * ReportTheme.TITLE * TITLE_LEADING + ReportTheme.SPACE_2
                + ReportTheme.LEAD * 1.3f;
    }

    private float codeHeight() throws IOException {
        return 2 * CODE_PADDING + ReportTheme.LABEL + ReportTheme.SPACE_1
                + codeLines().size() * ReportTheme.BODY * 1.3f;
    }
}
