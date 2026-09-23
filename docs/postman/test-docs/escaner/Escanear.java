// Turns a generated fixture PDF into what an insured actually uploads: a flatbed scan, or a phone
// photo of the printed page — and optionally tampers with it the way a doctored scan looks.
//
// The generators write perfect vector PDFs, and a vision model reading only those never meets the
// two things that matter in real life: noisy, tilted pages that are NOT fraud (visualFindings must
// stay empty), and a field pasted over a scan, which is. Uses PDFBox 3.0.3, the same library
// DocumentAnalyzerImpl rasterizes with, so the render here is the one the model sees.
//
// Run through escaner.js (it builds the classpath from ~/.m2), not by hand:
//   scan  <in.pdf> <out.pdf> <seed> [<find>|<replace> ...]   image-only PDF, like a scanner makes
//   photo <in.pdf> <out.jpg> <seed>                           JPEG, like a phone photo of the page
//   raw   <in.pdf> <out.rgba>                                 150 DPI render as raw RGBA (to verify)
//
// A <find>|<replace> pair pastes <replace> over the first occurrence of <find> after the scan
// degradation: a clean white box with crisp text in another typeface, level while the page is
// tilted. That's what a pasted field looks like, and what extraccion-documento-v5.md asks the
// model to report ("bordes, halos, bloques de color o resolución distinta alrededor de un dato").

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Escanear {

    private static final int SCAN_DPI = 200;
    private static final int PHOTO_DPI = 150;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        File in = new File(args[1]);
        File out = new File(args[2]);
        try (PDDocument doc = Loader.loadPDF(in)) {
            switch (mode) {
                case "scan" -> {
                    List<Patch> patches = new ArrayList<>();
                    for (int i = 4; i < args.length; i++) {
                        String[] pair = args[i].split("\\|", 2);
                        patches.add(locate(doc, pair[0], pair[1]));
                    }
                    scan(doc, out, Long.parseLong(args[3]), patches);
                }
                case "photo" -> photo(doc, out, new Random(Long.parseLong(args[3])));
                case "raw" -> raw(doc, out);
                default -> throw new IllegalArgumentException("Modo desconocido: " + mode);
            }
        }
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    private static void scan(PDDocument doc, File out, long seed, List<Patch> patches) throws IOException {
        Random rnd = new Random(seed);
        BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, SCAN_DPI, ImageType.RGB);
        int w = page.getWidth();
        int h = page.getHeight();

        // A page laid a little crooked on the glass: under 1.2°, either way.
        double angle = Math.toRadians((0.5 + rnd.nextDouble() * 0.7) * (rnd.nextBoolean() ? 1 : -1));
        AffineTransform tilt = AffineTransform.getRotateInstance(angle, w / 2.0, h / 2.0);

        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(page, tilt, null);
        g.dispose();

        canvas = blur(canvas);
        // Paper that isn't white, ink that isn't black, and sensor noise.
        tone(canvas, new int[]{241, 237, 226}, 42, 5.0, rnd);

        double scale = SCAN_DPI / 72.0;
        for (Patch patch : patches) {
            paste(canvas, patch, tilt, scale);
        }
        writePdf(canvas, out, 0.72f, seed);
    }

    /**
     * A pasted field: a white box (the paper around it is ~237, so it shows as a halo), crisp text in
     * Serif where the document uses Helvetica, and level where the page is tilted.
     */
    private static void paste(BufferedImage canvas, Patch patch, AffineTransform tilt, double scale) {
        Point2D baseline = tilt.transform(new Point2D.Double(patch.x * scale, patch.baseline * scale), null);
        int fontPx = (int) Math.round(patch.fontSize * scale);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.SERIF, patch.bold ? Font.BOLD : Font.PLAIN, fontPx);
        g.setFont(font);
        int textW = g.getFontMetrics().stringWidth(patch.replacement);
        int boxW = (int) Math.max(textW, patch.width * scale) + 8;
        int boxX = (int) baseline.getX() - 4;
        int boxY = (int) (baseline.getY() - fontPx * 1.05);
        g.setColor(Color.WHITE);
        g.fillRect(boxX, boxY, boxW, (int) (fontPx * 1.4));
        g.setColor(new Color(18, 18, 18));
        g.drawString(patch.replacement, (int) baseline.getX(), (int) baseline.getY());
        g.dispose();
    }

    // ── Photo ────────────────────────────────────────────────────────────────

    private static void photo(PDDocument doc, File out, Random rnd) throws IOException {
        BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, PHOTO_DPI, ImageType.RGB);
        int w = (int) (page.getWidth() * 1.3);
        int h = (int) (page.getHeight() * 1.22);

        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        // A wooden desk under the sheet.
        g.setPaint(new GradientPaint(0, 0, new Color(124, 94, 66), w, h, new Color(92, 68, 48)));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform hold = new AffineTransform();
        hold.translate(w / 2.0, h / 2.0);
        hold.rotate(Math.toRadians((2 + rnd.nextDouble() * 1.5) * (rnd.nextBoolean() ? 1 : -1)));
        hold.shear(0.03, 0.01); // a phone is never quite parallel to the table
        hold.scale(0.94, 0.94);
        hold.translate(-page.getWidth() / 2.0, -page.getHeight() / 2.0);
        g.drawImage(page, hold, null);
        g.dispose();

        canvas = blur(canvas);
        light(canvas, rnd);
        tone(canvas, new int[]{255, 250, 236}, 30, 7.0, rnd);
        writeJpeg(canvas, out, 0.8f);
    }

    /** Uneven light: brighter near one top corner, falling off towards the far edge. */
    private static void light(BufferedImage img, Random rnd) {
        int w = img.getWidth();
        int h = img.getHeight();
        double lx = rnd.nextBoolean() ? w * 0.2 : w * 0.8;
        double ly = h * 0.1;
        double maxD = Math.hypot(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double f = 1.05 - 0.32 * Math.hypot(x - lx, y - ly) / maxD;
                int rgb = img.getRGB(x, y);
                img.setRGB(x, y, rgb(((rgb >> 16) & 0xff) * f, ((rgb >> 8) & 0xff) * f, (rgb & 0xff) * f));
            }
        }
    }

    // ── Shared degradation ───────────────────────────────────────────────────

    private static BufferedImage blur(BufferedImage img) {
        float[] k = new float[9];
        java.util.Arrays.fill(k, 1f / 9f);
        return new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
    }

    /** Maps white to the paper tone and black to a dark grey, plus gaussian noise. */
    private static void tone(BufferedImage img, int[] paper, int ink, double noise, Random rnd) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                double n = rnd.nextGaussian() * noise;
                double r = ink + ((rgb >> 16) & 0xff) * (paper[0] - ink) / 255.0 + n;
                double gg = ink + ((rgb >> 8) & 0xff) * (paper[1] - ink) / 255.0 + n;
                double b = ink + (rgb & 0xff) * (paper[2] - ink) / 255.0 + n;
                img.setRGB(x, y, rgb(r, gg, b));
            }
        }
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int rgb(double r, double g, double b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    // ── Output ───────────────────────────────────────────────────────────────

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void writeJpeg(BufferedImage img, File out, float quality) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(jpeg(img, quality));
        }
    }

    /** One A4 page holding only the scanned image: no text layer, as a scanner delivers it. */
    private static void writePdf(BufferedImage img, File out, float quality, long seed) throws IOException {
        try (PDDocument scanned = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            scanned.addPage(page);
            PDImageXObject image = JPEGFactory.createFromByteArray(scanned, jpeg(img, quality));
            try (PDPageContentStream cs = new PDPageContentStream(scanned, page)) {
                cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            scanned.getDocumentInformation().setProducer("Arbiter test fixture (escaneo simulado)");
            scanned.getDocumentInformation().setKeywords("fixture de prueba, sistema Arbiter, no es un documento real");
            // Without a fixed ID PDFBox derives one from the clock, and every regeneration would
            // rewrite a binary that didn't change.
            scanned.setDocumentId(seed);
            scanned.save(out);
        }
    }

    private static void raw(PDDocument doc, File out) throws IOException {
        BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 150, ImageType.RGB);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(out))) {
            dos.writeInt(img.getWidth());
            dos.writeInt(img.getHeight());
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    dos.write((rgb >> 16) & 0xff);
                    dos.write((rgb >> 8) & 0xff);
                    dos.write(rgb & 0xff);
                    dos.write(255);
                }
            }
        }
    }

    // ── Locating the field to paste over ─────────────────────────────────────

    /** Where the text to replace sits on the page, in points from the top-left corner. */
    record Patch(double x, double baseline, double width, float fontSize, boolean bold, String replacement) {}

    private static Patch locate(PDDocument doc, String find, String replacement) throws IOException {
        Collector collector = new Collector();
        collector.getText(doc);
        // Whitespace is dropped on both sides: the stripper emits word gaps as separators, not glyphs.
        String haystack = collector.text.toString();
        String needle = find.replaceAll("\\s", "");
        int at = haystack.indexOf(needle);
        if (at < 0) {
            throw new IllegalArgumentException("No encontré \"" + find + "\" en el documento");
        }
        TextPosition first = collector.glyphs.get(at);
        TextPosition last = collector.glyphs.get(at + needle.length() - 1);
        double width = last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj();
        boolean bold = first.getFont().getName() != null && first.getFont().getName().contains("Bold");
        return new Patch(first.getXDirAdj(), first.getYDirAdj(), width, first.getFontSizeInPt(), bold, replacement);
    }

    private static final class Collector extends PDFTextStripper {
        final StringBuilder text = new StringBuilder();
        final List<TextPosition> glyphs = new ArrayList<>();

        Collector() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String ignored, List<TextPosition> positions) {
            for (TextPosition p : positions) {
                String unicode = p.getUnicode();
                for (int i = 0; i < unicode.length(); i++) {
                    if (!Character.isWhitespace(unicode.charAt(i))) {
                        text.append(unicode.charAt(i));
                        glyphs.add(p);
                    }
                }
            }
        }
    }
}
