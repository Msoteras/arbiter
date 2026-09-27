package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.List;

/**
 * The few things in the period somebody should actually look at, stated in words.
 *
 * <p>Each line is a fact read straight off the rows the report lists — never an interpretation. The
 * dot carries the semaphore tone of whatever the line is about.
 */
public record BulletsBlock(String label, List<Bullet> bullets) implements Block {

    private static final float PADDING = 10;
    private static final float DOT_RADIUS = 1.8f;
    private static final float LEADING = 1.35f;
    private static final float INDENT = 12;
    private static final float BULLET_GAP = ReportTheme.SPACE_2;

    public record Bullet(String text, Rgb tone) {}

    @Override
    public float height(float width) throws IOException {
        float total = 2 * PADDING + ReportTheme.LABEL + ReportTheme.SPACE_3;
        for (Bullet bullet : bullets) {
            total += lines(bullet, width).size() * ReportTheme.BODY * LEADING + BULLET_GAP;
        }
        return total - BULLET_GAP;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float height = height(width);
        canvas.panel(x, top - height, width, height, ReportTheme.RADIUS_CARD, ReportTheme.SURFACE,
                ReportTheme.BORDER);
        canvas.label(x + PADDING, top - PADDING - ReportTheme.LABEL, label, ReportTheme.MUTED);

        float y = top - PADDING - ReportTheme.LABEL - ReportTheme.SPACE_3;
        for (Bullet bullet : bullets) {
            canvas.dot(x + PADDING + DOT_RADIUS, y - ReportTheme.BODY / 2, DOT_RADIUS, bullet.tone(),
                    bullet.tone());
            for (String line : lines(bullet, width)) {
                canvas.text(x + PADDING + INDENT, y - ReportTheme.BODY, line,
                        PdfCanvas.Weight.REGULAR, ReportTheme.BODY, ReportTheme.INK_SOFT);
                y -= ReportTheme.BODY * LEADING;
            }
            y -= BULLET_GAP;
        }
    }

    private List<String> lines(Bullet bullet, float width) throws IOException {
        return PdfCanvas.wrap(bullet.text(), PdfCanvas.Weight.REGULAR, ReportTheme.BODY,
                width - 2 * PADDING - INDENT);
    }
}
