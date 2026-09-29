package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

public record SignatureBlock(List<String> captions) implements Block {

    private static final float RULE_GAP = ReportTheme.SPACE_5;
    private static final float COLUMN_GAP = ReportTheme.SPACE_6;

    @Override
    public float height(float width) {
        return RULE_GAP + ReportTheme.SPACE_2 + ReportTheme.NOTE * 1.2f;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float columnWidth = (width - COLUMN_GAP * (captions.size() - 1)) / captions.size();
        for (int i = 0; i < captions.size(); i++) {
            float columnX = x + i * (columnWidth + COLUMN_GAP);
            float ruleY = top - RULE_GAP;
            canvas.line(columnX, ruleY, columnX + columnWidth, ruleY, ReportTheme.BORDER_STRONG,
                    ReportTheme.HAIRLINE * 2);
            canvas.text(columnX, ruleY - ReportTheme.SPACE_2 - ReportTheme.NOTE,
                    PdfCanvas.fit(captions.get(i), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                            columnWidth),
                    PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED);
        }
    }
}
