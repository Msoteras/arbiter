package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PdfCanvas {

    public enum Weight {REGULAR, BOLD}

    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final String ELLIPSIS = "…";
    private static final float KAPPA = 0.5523f;

    private final PDPageContentStream content;

    public PdfCanvas(PDPageContentStream content) {
        this.content = content;
    }

    public void text(float x, float baseline, String value, Weight weight, float size, Rgb color)
            throws IOException {
        write(x, baseline, value, weight, size, color, 0);
    }

    public void textRight(float right, float baseline, String value, Weight weight, float size,
                          Rgb color) throws IOException {
        write(right - width(value, weight, size), baseline, value, weight, size, color, 0);
    }

    public void textCentred(float centre, float baseline, String value, Weight weight, float size,
                            Rgb color) throws IOException {
        write(centre - width(value, weight, size) / 2, baseline, value, weight, size, color, 0);
    }

    public void label(float x, float baseline, String value, Rgb color) throws IOException {
        write(x, baseline, value.toUpperCase(Locale.ROOT), Weight.BOLD, ReportTheme.LABEL, color,
                ReportTheme.LABEL_TRACKING);
    }

    public static float labelWidth(String value) throws IOException {
        String upper = value.toUpperCase(Locale.ROOT);
        return width(upper, Weight.BOLD, ReportTheme.LABEL)
                + ReportTheme.LABEL_TRACKING * printable(upper, font(Weight.BOLD)).length();
    }

    public static List<String> wrapLabel(String value, float maxWidth, int maxLines)
            throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : printable(value.toUpperCase(Locale.ROOT), font(Weight.BOLD)).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || labelWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        lines.add(line.toString());
        return lines.size() <= maxLines ? lines : lines.subList(0, maxLines);
    }

    private void write(float x, float baseline, String value, Weight weight, float size, Rgb color,
                       float tracking) throws IOException {
        content.beginText();
        content.setNonStrokingColor(color.red(), color.green(), color.blue());
        content.setFont(font(weight), size);
        if (tracking != 0) {
            content.setCharacterSpacing(tracking);
        }
        content.newLineAtOffset(x, baseline);
        content.showText(printable(value, font(weight)));
        content.endText();
        if (tracking != 0) {
            content.setCharacterSpacing(0);
        }
    }

    public void fillRect(float x, float y, float width, float height, Rgb color) throws IOException {
        content.setNonStrokingColor(color.red(), color.green(), color.blue());
        content.addRect(x, y, width, height);
        content.fill();
    }

    public void panel(float x, float y, float width, float height, float radius, Rgb fill, Rgb border)
            throws IOException {
        if (fill != null) {
            roundedPath(x, y, width, height, radius);
            content.setNonStrokingColor(fill.red(), fill.green(), fill.blue());
            content.fill();
        }
        if (border != null) {
            roundedPath(x, y, width, height, radius);
            content.setStrokingColor(border.red(), border.green(), border.blue());
            content.setLineWidth(ReportTheme.HAIRLINE);
            content.stroke();
        }
    }

    public void line(float fromX, float fromY, float toX, float toY, Rgb color, float thickness)
            throws IOException {
        content.setStrokingColor(color.red(), color.green(), color.blue());
        content.setLineWidth(thickness);
        content.moveTo(fromX, fromY);
        content.lineTo(toX, toY);
        content.stroke();
    }

    public void polyline(List<float[]> points, Rgb color, float thickness) throws IOException {
        if (points.size() < 2) {
            return;
        }
        content.setStrokingColor(color.red(), color.green(), color.blue());
        content.setLineWidth(thickness);
        content.setLineCapStyle(1);
        content.moveTo(points.getFirst()[0], points.getFirst()[1]);
        for (float[] point : points.subList(1, points.size())) {
            content.lineTo(point[0], point[1]);
        }
        content.stroke();
        content.setLineCapStyle(0);
    }

    public void dot(float centreX, float centreY, float radius, Rgb fill, Rgb border)
            throws IOException {
        circlePath(centreX, centreY, radius);
        if (fill != null) {
            content.setNonStrokingColor(fill.red(), fill.green(), fill.blue());
            content.fill();
            circlePath(centreX, centreY, radius);
        }
        content.setStrokingColor(border.red(), border.green(), border.blue());
        content.setLineWidth(1.2f);
        content.stroke();
    }

    private void roundedPath(float x, float y, float width, float height, float radius)
            throws IOException {
        float r = Math.min(radius, Math.min(width, height) / 2);
        float handle = r * KAPPA;
        content.moveTo(x + r, y);
        content.lineTo(x + width - r, y);
        content.curveTo(x + width - r + handle, y, x + width, y + r - handle, x + width, y + r);
        content.lineTo(x + width, y + height - r);
        content.curveTo(x + width, y + height - r + handle, x + width - r + handle, y + height,
                x + width - r, y + height);
        content.lineTo(x + r, y + height);
        content.curveTo(x + r - handle, y + height, x, y + height - r + handle, x, y + height - r);
        content.lineTo(x, y + r);
        content.curveTo(x, y + r - handle, x + r - handle, y, x + r, y);
        content.closePath();
    }

    private void circlePath(float centreX, float centreY, float radius) throws IOException {
        float handle = radius * KAPPA;
        content.moveTo(centreX + radius, centreY);
        content.curveTo(centreX + radius, centreY + handle, centreX + handle, centreY + radius,
                centreX, centreY + radius);
        content.curveTo(centreX - handle, centreY + radius, centreX - radius, centreY + handle,
                centreX - radius, centreY);
        content.curveTo(centreX - radius, centreY - handle, centreX - handle, centreY - radius,
                centreX, centreY - radius);
        content.curveTo(centreX + handle, centreY - radius, centreX + radius, centreY - handle,
                centreX + radius, centreY);
        content.closePath();
    }

    public PDPageContentStream stream() {
        return content;
    }

    public static float width(String value, Weight weight, float size) throws IOException {
        PDFont font = font(weight);
        return font.getStringWidth(printable(value, font)) / 1000 * size;
    }

    public static String fit(String value, Weight weight, float size, float maxWidth)
            throws IOException {
        String text = printable(value, font(weight));
        if (width(text, weight, size) <= maxWidth) {
            return text;
        }
        while (!text.isEmpty() && width(text + ELLIPSIS, weight, size) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ELLIPSIS;
    }

    public static List<String> wrap(String value, Weight weight, float size, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : printable(value, font(weight)).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || width(candidate, weight, size) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(fit(line.toString(), weight, size, maxWidth));
                line.setLength(0);
                line.append(word);
            }
        }
        lines.add(fit(line.toString(), weight, size, maxWidth));
        return lines;
    }

    public static List<String> wrap(String value, Weight weight, float size, float maxWidth,
                                    int maxLines) throws IOException {
        List<String> lines = wrap(value, weight, size, maxWidth);
        if (lines.size() <= maxLines) {
            return lines;
        }
        List<String> capped = new ArrayList<>(lines.subList(0, maxLines - 1));
        capped.add(fit(String.join(" ", lines.subList(maxLines - 1, lines.size())), weight, size,
                maxWidth));
        return capped;
    }

    private static PDFont font(Weight weight) {
        return weight == Weight.BOLD ? BOLD : REGULAR;
    }

    /** The standard 14 fonts only encode WinAnsi: one stray glyph makes showText lose the file. */
    private static String printable(String value, PDFont font) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String glyph = Character.isWhitespace(codePoint) ? " " : Character.toString(codePoint);
            out.append(canEncode(font, glyph) ? glyph : "?");
        });
        return out.toString();
    }

    private static boolean canEncode(PDFont font, String glyph) {
        try {
            font.encode(glyph);
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }
}
