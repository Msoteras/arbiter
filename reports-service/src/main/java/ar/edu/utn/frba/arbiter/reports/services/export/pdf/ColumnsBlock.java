package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;

/**
 * Two blocks side by side, divided by a hairline — the chart and its distributions on the summary
 * page, the cross-tab and the glossary on the detail page.
 *
 * <p>Never split across pages: a pair placed together is placed together because it is read
 * together.
 */
public record ColumnsBlock(Block left, Block right, float leftFraction) implements Block {

    private static final float GAP = ReportTheme.SPACE_5;

    public static ColumnsBlock evenly(Block left, Block right) {
        return new ColumnsBlock(left, right, 0.5f);
    }

    @Override
    public float height(float width) throws IOException {
        return Math.max(left.height(leftWidth(width)), right.height(rightWidth(width)));
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float leftWidth = leftWidth(width);
        left.draw(canvas, x, top, leftWidth);
        right.draw(canvas, x + leftWidth + GAP, top, rightWidth(width));
        float divider = x + leftWidth + GAP / 2;
        canvas.line(divider, top, divider, top - height(width), ReportTheme.BORDER,
                ReportTheme.HAIRLINE);
    }

    private float leftWidth(float width) {
        return (width - GAP) * leftFraction;
    }

    private float rightWidth(float width) {
        return width - GAP - leftWidth(width);
    }
}
