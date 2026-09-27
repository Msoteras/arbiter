package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * How the headline figure moved across the period, one point per bucket.
 *
 * <p>A bucket the metric could not be computed for is drawn hollow on the baseline and breaks the
 * line, rather than being joined through as if it were a zero — the difference between "nothing was
 * resolved" and "everything resolved instantly" is the whole point of the chart.
 */
public record TimelineChartBlock(String label, List<Point> points, DoubleFunction<String> format,
                                 String emptyMessage) implements Block {

    private static final float PLOT_HEIGHT = 62;
    private static final float AXIS_WIDTH = 54;
    private static final float DOT_RADIUS = 2.6f;
    private static final float VALUE_ROW = ReportTheme.NOTE * 1.4f;
    private static final int AXIS_TICKS = 3;

    /**
     * @param value null when the bucket has nothing to average
     * @param note  what the point is computed over, e.g. "3 cierres"
     */
    public record Point(String bucket, Double value, String note) {}

    @Override
    public float height(float width) {
        if (!hasValues()) {
            return ReportTheme.LABEL + ReportTheme.SPACE_3 + ReportTheme.BODY * 2;
        }
        return ReportTheme.LABEL + ReportTheme.SPACE_3 + VALUE_ROW + PLOT_HEIGHT
                + ReportTheme.SPACE_2 + ReportTheme.NOTE * 1.25f + ReportTheme.NOTE * 1.25f;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        canvas.label(x, top - ReportTheme.LABEL, label, ReportTheme.MUTED);
        if (!hasValues()) {
            canvas.text(x, top - ReportTheme.LABEL - ReportTheme.SPACE_3 - ReportTheme.BODY,
                    PdfCanvas.fit(emptyMessage, PdfCanvas.Weight.REGULAR, ReportTheme.BODY, width),
                    PdfCanvas.Weight.REGULAR, ReportTheme.BODY, ReportTheme.MUTED);
            return;
        }

        float plotTop = top - ReportTheme.LABEL - ReportTheme.SPACE_3 - VALUE_ROW;
        float plotBottom = plotTop - PLOT_HEIGHT;
        float plotLeft = x + AXIS_WIDTH;
        float plotWidth = width - AXIS_WIDTH;
        double max = maximum();

        for (int tick = 0; tick < AXIS_TICKS; tick++) {
            float ratio = tick / (float) (AXIS_TICKS - 1);
            float y = plotBottom + PLOT_HEIGHT * ratio;
            canvas.line(plotLeft, y, plotLeft + plotWidth, y, ReportTheme.BORDER_SUBTLE,
                    ReportTheme.HAIRLINE);
            canvas.textRight(x + AXIS_WIDTH - ReportTheme.SPACE_2, y - ReportTheme.NOTE / 2,
                    format.apply(max * ratio), PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                    ReportTheme.MUTED_SOFT);
        }

        // The points sit inside the gridlines by a dot's width, so the first and last are not
        // sliced in half by the edge of the plot.
        float inset = DOT_RADIUS + 1;
        float pointsLeft = plotLeft + inset;
        float pointsWidth = plotWidth - 2 * inset;
        float step = points.size() == 1 ? 0 : pointsWidth / (points.size() - 1);
        float first = points.size() == 1 ? plotLeft + plotWidth / 2 : pointsLeft;
        int labelStep = labelStep(step);

        List<float[]> run = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            float centre = first + step * i;
            if (point.value() == null) {
                if (run.size() > 1) {
                    canvas.polyline(run, ReportTheme.ACCENT_STRONG, 1.4f);
                }
                run = new ArrayList<>();
                continue;
            }
            run.add(new float[]{centre, plotBottom + (float) (PLOT_HEIGHT * point.value() / max)});
        }
        if (run.size() > 1) {
            canvas.polyline(run, ReportTheme.ACCENT_STRONG, 1.4f);
        }

        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            float centre = first + step * i;
            boolean missing = point.value() == null;
            float dotY = missing ? plotBottom
                    : plotBottom + (float) (PLOT_HEIGHT * point.value() / max);
            canvas.dot(centre, dotY, DOT_RADIUS, missing ? ReportTheme.SURFACE : ReportTheme.SURFACE,
                    missing ? ReportTheme.MUTED_SOFT : ReportTheme.ACCENT_STRONG);
            if (!missing) {
                canvas.dot(centre, dotY, DOT_RADIUS - 1.1f, ReportTheme.ACCENT_STRONG,
                        ReportTheme.ACCENT_STRONG);
            }

            if (i % labelStep != 0) {
                continue;
            }
            if (!missing) {
                canvas.textCentred(centre, dotY + DOT_RADIUS + ReportTheme.SPACE_1,
                        format.apply(point.value()), PdfCanvas.Weight.BOLD, ReportTheme.NOTE,
                        ReportTheme.INK);
            }
            canvas.textCentred(clamped(centre, point.bucket(), x, width),
                    plotBottom - ReportTheme.SPACE_2 - ReportTheme.NOTE, point.bucket(),
                    PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.INK_SOFT);
            canvas.textCentred(clamped(centre, point.note(), x, width),
                    plotBottom - ReportTheme.SPACE_2 - ReportTheme.NOTE * 2.25f, point.note(),
                    PdfCanvas.Weight.REGULAR, ReportTheme.NOTE, ReportTheme.MUTED_SOFT);
        }
    }

    /**
     * Nudges an axis label back inside the block. The last bucket's label is wider than the gap it
     * has left, and centring it on the point would run it past the column's edge.
     */
    private static float clamped(float centre, String text, float x, float width) throws IOException {
        float half = PdfCanvas.width(text, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE) / 2;
        return Math.min(Math.max(centre, x + half), x + width - half);
    }

    /** Thins the axis out until its labels stop colliding; a year of weeks is 53 buckets. */
    private int labelStep(float step) {
        if (step <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(34 / step));
    }

    private boolean hasValues() {
        return points.stream().anyMatch(point -> point.value() != null);
    }

    /** Never zero: the plot divides by it, and a flat period would otherwise vanish. */
    private double maximum() {
        double max = points.stream()
                .filter(point -> point.value() != null)
                .mapToDouble(Point::value)
                .max()
                .orElse(1);
        return max <= 0 ? 1 : max;
    }
}
