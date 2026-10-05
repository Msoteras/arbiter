package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Arbiter symbol from {@code arbiter-frontend/public/brand/arbiter-symbol-black.svg}, on its 88x88
 * viewBox. Drawn rather than embedded: PDFBox has no SVG rasteriser. Arcs are polylines because the
 * big one is interrupted by a mask, which a content stream cannot express.
 */
public final class ArbiterMark {

    private static final float VIEWBOX = 88;

    private static final float CENTER = 44;
    private static final float RADIUS = 34;

    private static final float RING_X = 21;
    private static final float RING_Y = 67;
    private static final float RING_GAP = 13.5f;
    private static final float RING_RADIUS = 9;
    private static final float DOT_RADIUS = 3;

    private static final float ARC_FROM = -71;
    private static final float ARC_SWEEP = -308;
    private static final float SPARK_FROM = -61.9f;
    private static final float SPARK_SWEEP = 31.9f;

    private static final float[][] SPARK_LINES = {
            {70.0f, 9.8f, 65.2f, 29.2f},
            {77.2f, 16.8f, 58.0f, 22.3f}};

    private static final float STROKE = 4.5f;
    private static final float SPARK_STROKE = 4;

    private static final float STEP_DEGREES = 2;
    private static final int CIRCLE_STEPS = 64;

    private ArbiterMark() {
    }

    public static void draw(PDPageContentStream content, float x, float y, float size, Rgb color)
            throws IOException {
        float scale = size / VIEWBOX;
        content.setStrokingColor(color.red(), color.green(), color.blue());
        content.setNonStrokingColor(color.red(), color.green(), color.blue());
        content.setLineCapStyle(0);

        content.setLineWidth(STROKE * scale);
        strokeArc(content, x, y, scale, ARC_FROM, ARC_SWEEP, true);
        circlePath(content, x + RING_X * scale, y + (VIEWBOX - RING_Y) * scale, RING_RADIUS * scale);
        content.stroke();

        content.setLineWidth(SPARK_STROKE * scale);
        strokeArc(content, x, y, scale, SPARK_FROM, SPARK_SWEEP, false);
        for (float[] line : SPARK_LINES) {
            content.moveTo(x + line[0] * scale, y + (VIEWBOX - line[1]) * scale);
            content.lineTo(x + line[2] * scale, y + (VIEWBOX - line[3]) * scale);
            content.stroke();
        }

        circlePath(content, x + RING_X * scale, y + (VIEWBOX - RING_Y) * scale, DOT_RADIUS * scale);
        content.fill();
    }

    private static void strokeArc(PDPageContentStream content, float x, float y, float scale,
                                  float fromDegrees, float sweepDegrees, boolean leaveTheRingGap)
            throws IOException {
        int steps = Math.round(Math.abs(sweepDegrees) / STEP_DEGREES);
        float step = sweepDegrees / steps;
        List<float[]> run = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double radians = Math.toRadians(fromDegrees + i * step);
            float sx = (float) (CENTER + RADIUS * Math.cos(radians));
            float sy = (float) (CENTER + RADIUS * Math.sin(radians));
            if (leaveTheRingGap && Math.hypot(sx - RING_X, sy - RING_Y) <= RING_GAP) {
                strokeRun(content, run);
                run = new ArrayList<>();
                continue;
            }
            run.add(new float[]{x + sx * scale, y + (VIEWBOX - sy) * scale});
        }
        strokeRun(content, run);
    }

    private static void strokeRun(PDPageContentStream content, List<float[]> run) throws IOException {
        if (run.size() < 2) {
            return;
        }
        content.moveTo(run.getFirst()[0], run.getFirst()[1]);
        for (float[] point : run.subList(1, run.size())) {
            content.lineTo(point[0], point[1]);
        }
        content.stroke();
    }

    private static void circlePath(PDPageContentStream content, float cx, float cy, float radius)
            throws IOException {
        for (int i = 0; i < CIRCLE_STEPS; i++) {
            double radians = 2 * Math.PI * i / CIRCLE_STEPS;
            float px = (float) (cx + radius * Math.cos(radians));
            float py = (float) (cy + radius * Math.sin(radians));
            if (i == 0) {
                content.moveTo(px, py);
            } else {
                content.lineTo(px, py);
            }
        }
        content.closePath();
    }
}
